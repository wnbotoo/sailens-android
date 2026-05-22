package com.friady.sailens.data.source.ml.instance

import android.content.Context
import android.util.Log
import com.friady.sailens.data.source.ml.FramePreprocessor
import com.friady.sailens.data.source.ml.OpenCVImageProcessor
import com.friady.sailens.data.source.ml.semantic.SegmenterConfig
import com.friady.sailens.domain.config.PerceptionConfig
import com.friady.sailens.domain.model.perception.DetectedInstance
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.repository.InstanceSegmentationProvider
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
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
    private var inputBuffers: List<com.google.ai.edge.litert.TensorBuffer>? = null
    private var outputBuffers: List<com.google.ai.edge.litert.TensorBuffer>? = null
    private var processor: FramePreprocessor? = null
    private var cachedInput: FloatArray = FloatArray(0)
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

    override suspend fun detect(frame: ImageFrame): List<DetectedInstance> {
        if (!_isInitialized) return emptyList()

        return withContext(singleThreadDispatcher) {
            if (!isActive) throw CancellationException("Coroutine cancelled")

            val activeInputBuffers = inputBuffers ?: return@withContext emptyList()
            val activeOutputBuffers = outputBuffers ?: return@withContext emptyList()
            val activeModel = model ?: return@withContext emptyList()
            val activeProcessor = processor ?: return@withContext emptyList()
            val activePostProcessor = postProcessor ?: return@withContext emptyList()

            activeProcessor.preprocess(frame, frame.rotationDegrees, cachedInput)
            activeInputBuffers[0].writeFloat(cachedInput)
            activeModel.run(activeInputBuffers, activeOutputBuffers)

            val detectionTensor = activeOutputBuffers.firstOrNull()?.readFloat() ?: return@withContext emptyList()
            val prototypeTensor = activeOutputBuffers.getOrNull(1)?.readFloat()

            if (!hasLoggedTensorInfo) {
                Log.i(
                    TAG,
                    "YOLO26-seg runtime tensors: outputs=${activeOutputBuffers.size}, detectionValues=${detectionTensor.size}, prototypeValues=${prototypeTensor?.size ?: 0}, frame=${frame.width}x${frame.height}, rotation=${frame.rotationDegrees}"
                )
                hasLoggedTensorInfo = true
            }

            activePostProcessor.postProcess(frame, detectionTensor, prototypeTensor)
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
        val inputDimensions = requireNotNull(model.getInputTensorType(modelConfig.inputTensorName).layout) {
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

        processor = OpenCVImageProcessor(inputConfig)
        cachedInput = FloatArray(inputSpec.width * inputSpec.height * inputSpec.channels)
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
            "YOLO26 instance tensor config: input=${inputSpec.width}x${inputSpec.height}x${inputSpec.channels}"
        )
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
        cachedInput = FloatArray(0)
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
