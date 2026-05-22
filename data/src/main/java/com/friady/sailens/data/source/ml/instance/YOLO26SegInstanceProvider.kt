package com.friady.sailens.data.source.ml.instance

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.friady.sailens.data.source.ml.ModelInputDataType
import com.friady.sailens.data.source.ml.YoloInputPreprocessor
import com.friady.sailens.data.source.ml.resolveModelInputDataType
import com.friady.sailens.data.source.ml.semantic.SegmenterConfig
import com.friady.sailens.domain.config.PerceptionConfig
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.InstanceSegmentationOutput
import com.friady.sailens.domain.repository.InstanceSegmentationProvider
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "YOLO26SegProvider"

/**
 * YOLO26 instance segmentation provider.
 *
 * 当前职责聚焦“有什么、在哪里”：
 * - 使用 YOLO26-seg 的 bbox + class + confidence 驱动障碍物检测/跟踪
 * - mask 当前在 domain 链路中不是硬依赖，因此先返回 null，保证实时性和稳定性
 */
class YOLO26SegInstanceProvider(
    private val context: Context,
    private val perceptionConfig: PerceptionConfig,
    private val modelConfig: YOLO26SegModelConfig = YOLO26SegModelConfig(),
) : InstanceSegmentationProvider {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleThreadDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var model: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer>? = null
    private var outputBuffers: List<TensorBuffer>? = null
    private var processor: YoloInputPreprocessor? = null
    private var inputDataType: ModelInputDataType = ModelInputDataType.FLOAT32
    private var cachedFloatInput: FloatArray = FloatArray(0)
    private var cachedInt8Input: ByteArray = ByteArray(0)
    private var postProcessor: YOLO26SegPostProcessor? = null
    private var hasLoggedTensorInfo: Boolean = false

    @Volatile
    private var _isInitialized = false

    override val isInitialized: Boolean
        get() = _isInitialized

    override suspend fun initialize() {
        if (_isInitialized) return

        withContext(singleThreadDispatcher) {
            cleanupInternal()

            val initializedModel = runCatching {
                Log.i(TAG, "Initializing YOLO26 instance model with GPU accelerator")
                createCompiledModel(Accelerator.GPU)
            }.recoverCatching { gpuError ->
                Log.w(TAG, "GPU initialization failed, retrying with CPU", gpuError)
                createCompiledModel(Accelerator.CPU)
            }.getOrElse { error ->
                throw IllegalStateException("Failed to initialize YOLO26 instance model", error)
            }

            try {
                model = initializedModel
                inputBuffers = initializedModel.createInputBuffers()
                outputBuffers = initializedModel.createOutputBuffers()
                configurePreAndPostProcessing(initializedModel)
                _isInitialized = true
                Log.i(TAG, "YOLO26 instance model initialized")
            } catch (error: Throwable) {
                cleanupInternal()
                throw error
            }
        }
    }

    override suspend fun detect(frame: ImageFrame): InstanceSegmentationOutput {
        if (!_isInitialized) return InstanceSegmentationOutput(emptyList())

        return withContext(singleThreadDispatcher) {
            if (!isActive) throw CancellationException("Coroutine cancelled")

            val activeInputBuffers = inputBuffers ?: return@withContext InstanceSegmentationOutput(emptyList())
            val activeOutputBuffers = outputBuffers ?: return@withContext InstanceSegmentationOutput(emptyList())
            val activeModel = model ?: return@withContext InstanceSegmentationOutput(emptyList())
            val activeProcessor = processor ?: return@withContext InstanceSegmentationOutput(emptyList())
            val activePostProcessor = postProcessor ?: return@withContext InstanceSegmentationOutput(emptyList())
            val activeInputDataType = inputDataType

            val startTime = SystemClock.uptimeMillis()
            preprocessInput(activeProcessor, frame, activeInputDataType)
            val afterPreprocessTime = SystemClock.uptimeMillis()

            writeInput(activeInputBuffers[0], activeInputDataType)
            activeModel.run(activeInputBuffers, activeOutputBuffers)
            val afterModelTime = SystemClock.uptimeMillis()

            val detectionTensor = activeOutputBuffers.firstOrNull()?.readFloat()
                ?: return@withContext InstanceSegmentationOutput(emptyList())
            val prototypeTensor = activeOutputBuffers.getOrNull(1)?.readFloat()
            val afterOutputReadTime = SystemClock.uptimeMillis()

            if (!hasLoggedTensorInfo) {
                Log.i(
                    TAG,
                    "YOLO26-seg runtime tensors: outputs=${activeOutputBuffers.size}, detectionValues=${detectionTensor.size}, prototypeValues=${prototypeTensor?.size ?: 0}, frame=${frame.width}x${frame.height}, rotation=${frame.rotationDegrees}, inputType=$activeInputDataType"
                )
                hasLoggedTensorInfo = true
            }

            val instances = activePostProcessor.postProcess(frame, detectionTensor, prototypeTensor)
            val afterPostprocessTime = SystemClock.uptimeMillis()

            InstanceSegmentationOutput(
                instances = instances,
                preprocessTimeMs = afterPreprocessTime - startTime,
                inferenceTimeMs = afterModelTime - afterPreprocessTime,
                outputReadTimeMs = afterOutputReadTime - afterModelTime,
                postprocessTimeMs = afterPostprocessTime - afterOutputReadTime,
            )
        }
    }

    override fun release() {
        runBlocking(singleThreadDispatcher) {
            cleanupInternal()
        }
    }

    private fun createCompiledModel(accelerator: Accelerator): CompiledModel {
        ensureModelAssetAvailable()
        return CompiledModel.create(
            context.assets,
            modelConfig.assetPath,
            CompiledModel.Options(accelerator),
        )
    }

    private fun configurePreAndPostProcessing(model: CompiledModel) {
        val inputTensorType = model.getInputTensorType(modelConfig.inputTensorName)
        val resolvedInputDataType = resolveModelInputDataType(
            configured = modelConfig.inputDataType,
            tensorElementType = inputTensorType.elementType,
        )
        val inputDimensions = requireNotNull(inputTensorType.layout) {
            "YOLO26 instance input tensor '${modelConfig.inputTensorName}' has no layout"
        }.dimensions
        val inputSpec = NhwcInputTensorSpec.from(inputDimensions)
        require(inputSpec.width == inputSpec.height) {
            "YOLO26 instance post-processor expects square input, got ${inputSpec.width}x${inputSpec.height}"
        }

        val inputConfig = SegmenterConfig(
            inputWidth = inputSpec.width,
            inputHeight = inputSpec.height,
            outputWidth = inputSpec.width,
            outputHeight = inputSpec.height,
            outputChannels = 1,
            mean = Triple(0f, 0f, 0f),
            std = Triple(1f, 1f, 1f),
            confidenceThreshold = perceptionConfig.minObstacleConfidence,
        )

        inputDataType = resolvedInputDataType.dataType
        val inputElementCount = inputSpec.width * inputSpec.height * inputSpec.channels
        cachedFloatInput = FloatArray(if (inputDataType == ModelInputDataType.FLOAT32) inputElementCount else 0)
        cachedInt8Input = ByteArray(if (inputDataType == ModelInputDataType.INT8) inputElementCount else 0)
        processor = YoloInputPreprocessor(
            config = inputConfig,
            inputQuantization = modelConfig.inputQuantization,
            preferNativeYuvPreprocessing = modelConfig.preferNativeYuvPreprocessing,
        )
        postProcessor = YOLO26SegPostProcessor(
            inputSize = inputSpec.width,
            classCount = modelConfig.classCount,
            maskCoefficientCount = modelConfig.maskCoefficientCount,
            confidenceThreshold = perceptionConfig.minObstacleConfidence,
            maxDetections = perceptionConfig.maxObstacles,
            enableMaskReconstruction = false,
        )

        Log.i(
            TAG,
            "YOLO26 instance tensor config: input=${inputSpec.width}x${inputSpec.height}x${inputSpec.channels}, inputType=${resolvedInputDataType.dataType}, tensorElement=${resolvedInputDataType.elementTypeName}"
        )
    }

    private fun preprocessInput(
        processor: YoloInputPreprocessor,
        frame: ImageFrame,
        activeInputDataType: ModelInputDataType,
    ) {
        when (activeInputDataType) {
            ModelInputDataType.FLOAT32 -> processor.preprocessFloat(
                frame = frame,
                rotationDegrees = frame.rotationDegrees,
                outputArray = cachedFloatInput,
            )

            ModelInputDataType.INT8 -> processor.preprocessInt8(
                frame = frame,
                rotationDegrees = frame.rotationDegrees,
                outputArray = cachedInt8Input,
            )

            ModelInputDataType.AUTO -> error("AUTO input type must be resolved before detection")
        }
    }

    private fun writeInput(
        inputBuffer: TensorBuffer,
        activeInputDataType: ModelInputDataType,
    ) {
        when (activeInputDataType) {
            ModelInputDataType.FLOAT32 -> inputBuffer.writeFloat(cachedFloatInput)
            ModelInputDataType.INT8 -> inputBuffer.writeInt8(cachedInt8Input)
            ModelInputDataType.AUTO -> error("AUTO input type must be resolved before detection")
        }
    }

    private fun ensureModelAssetAvailable() {
        try {
            context.assets.open(modelConfig.assetPath).use { }
        } catch (error: Exception) {
            throw IllegalStateException("Model asset '${modelConfig.assetPath}' is not packaged in app assets.", error)
        }
    }

    private fun cleanupInternal() {
        processor?.close()
        processor = null
        cachedFloatInput = FloatArray(0)
        cachedInt8Input = ByteArray(0)
        inputDataType = ModelInputDataType.FLOAT32
        postProcessor = null
        inputBuffers?.forEach { it.close() }
        outputBuffers?.forEach { it.close() }
        inputBuffers = null
        outputBuffers = null
        model?.close()
        model = null
        hasLoggedTensorInfo = false
        _isInitialized = false
    }

    private data class NhwcInputTensorSpec(
        val width: Int,
        val height: Int,
        val channels: Int,
    ) {
        companion object {
            fun from(dimensions: List<Int>): NhwcInputTensorSpec {
                require(dimensions.size == 4 && dimensions.last() == 3) {
                    "YOLO26 instance input must be NHWC RGB, got dimensions=$dimensions"
                }
                return NhwcInputTensorSpec(
                    width = dimensions[2],
                    height = dimensions[1],
                    channels = dimensions[3],
                )
            }
        }
    }
}
