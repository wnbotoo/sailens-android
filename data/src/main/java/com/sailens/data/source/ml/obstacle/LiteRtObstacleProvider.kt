package com.sailens.data.source.ml.obstacle

import android.content.Context
import android.os.SystemClock
import com.sailens.runtime.CatalogModelSourceResolver
import com.sailens.runtime.InputPreprocessBackend
import com.sailens.runtime.ModelInputDataType
import com.sailens.runtime.ModelSourceResolver
import com.sailens.runtime.ModelType
import com.sailens.runtime.TensorQuantization
import com.sailens.runtime.TfliteModelMetadata
import com.sailens.runtime.TfliteModelMetadataReader
import com.sailens.runtime.TfliteTensorMetadata
import com.sailens.runtime.TfliteTensorElementType
import com.sailens.runtime.ModelInputPreprocessor
import com.sailens.runtime.InputPreprocessCache
import com.sailens.runtime.ModelTensorConfig
import com.sailens.runtime.imageTensorSpec
import com.sailens.runtime.resolveModelInputDataType
import com.sailens.runtime.session.AcceleratorSelection
import com.sailens.runtime.session.LiteRtSession
import com.sailens.runtime.session.LiteRtSessionFactory
import com.sailens.domain.config.PerceptionConfig
import com.sailens.core.frame.ImageFrame
import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.domain.model.perception.ObstacleModelOutput
import com.sailens.domain.repository.ObstacleProvider
import com.sailens.core.log.LogService
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "LiteRtObstacleProvider"

/**
 * LiteRT obstacle detection provider: a single bbox-head detection network.
 */
class LiteRtObstacleProvider(
    private val context: Context,
    private val perceptionConfig: PerceptionConfig,
    private val modelConfig: ObstacleModelConfig = ObstacleModelConfig(),
    private val modelSourceResolver: ModelSourceResolver = CatalogModelSourceResolver,
    private val preprocessCache: InputPreprocessCache? = null,
    private val logService: LogService,
) : ObstacleProvider {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleThreadDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var session: LiteRtSession? = null
    private var inputBuffer: TensorBuffer? = null
    private var detectionBuffer: TensorBuffer? = null
    private var processor: ModelInputPreprocessor? = null
    private var inputDataType: ModelInputDataType = ModelInputDataType.FLOAT32
    private var cachedFloatInput: FloatArray = FloatArray(0)
    private var cachedInt8Input: ByteArray = ByteArray(0)
    private var postProcessor: ObstaclePostProcessor? = null
    private var resolvedTensorBindings: ResolvedTensorBindings? = null
    private var detectionBufferHandle: Long = 0L
    private var hasLoggedTensorInfo: Boolean = false

    @Volatile
    private var _isInitialized = false

    override val isInitialized: Boolean
        get() = _isInitialized

    override suspend fun initialize() {
        if (_isInitialized) return

        withContext(singleThreadDispatcher) {
            cleanupInternal()
            try {
                initializeSession()
                _isInitialized = true
                logService.info(TAG, "Obstacle model initialized with ${session?.accelerator}")
            } catch (error: CancellationException) {
                cleanupInternal()
                throw error
            } catch (error: Exception) {
                cleanupInternal()
                throw IllegalStateException("Failed to initialize obstacle model", error)
            } catch (error: UnsatisfiedLinkError) {
                cleanupInternal()
                throw IllegalStateException("Failed to initialize obstacle model", error)
            }
        }
    }

    private fun initializeSession() {
        val createdSession = LiteRtSessionFactory.create(
            context = context,
            sourceResolver = { accelerator ->
                modelSourceResolver.source(ModelType.OBSTACLE_DETECTION, accelerator)
            },
            selection = acceleratorSelection(),
            logTag = TAG,
            modelLabel = "obstacle model",
            logService = logService,
        )
        try {
            val metadata = TfliteModelMetadataReader.read(context, createdSession.source)
            val tensorBindings = configurePreAndPostProcessing(metadata)
            resolvedTensorBindings = tensorBindings
            session = createdSession
            inputBuffer = createdSession.inputBuffers[tensorBindings.inputBufferIndex]
            detectionBuffer = createdSession.outputBuffers[tensorBindings.detectionBufferIndex]
            detectionBufferHandle = extractTensorBufferHandle(
                createdSession.outputBuffers[tensorBindings.detectionBufferIndex]
            )
        } catch (error: CancellationException) {
            createdSession.close()
            throw error
        } catch (error: Exception) {
            createdSession.close()
            throw error
        } catch (error: UnsatisfiedLinkError) {
            createdSession.close()
            throw error
        }
    }

    private fun acceleratorSelection(): AcceleratorSelection = AcceleratorSelection(
        mode = modelConfig.acceleratorSelectionMode,
        preferredBackend = modelConfig.acceleratorBackend,
        fallbackOrder = ACCELERATOR_FALLBACK_ORDER,
    )

    // Raw LiteRtTensorBuffer* handle for the detection output, extracted once via reflection
    // (JniHandle accessor, LiteRT 2.1.3+). Non-zero only when available; enables the zero-copy
    // realtime postprocess path that skips the per-frame readInt8() allocation.
    private fun extractTensorBufferHandle(buffer: TensorBuffer): Long = runCatching {
        val cls = Class.forName("com.google.ai.edge.litert.JniHandle")
        val method = cls.getDeclaredMethod(
            "getHandle\$third_party_odml_litert_litert_kotlin_litert_kotlin_api"
        )
        method.isAccessible = true
        method.invoke(buffer) as Long
    }.getOrDefault(0L)

    override suspend fun detect(frame: ImageFrame): ObstacleModelOutput {
        if (!_isInitialized) return ObstacleModelOutput(emptyList())

        return withContext(singleThreadDispatcher) {
            if (!isActive) throw CancellationException("Coroutine cancelled")

            detectInitialized(frame)
        }
    }

    private fun detectInitialized(frame: ImageFrame): ObstacleModelOutput {
        val activeInputBuffer = inputBuffer ?: return ObstacleModelOutput(emptyList())
        val activeDetectionBuffer = detectionBuffer ?: return ObstacleModelOutput(emptyList())
        val activeSession = session ?: return ObstacleModelOutput(emptyList())
        val activeProcessor = processor ?: return ObstacleModelOutput(emptyList())
        val activePostProcessor = postProcessor ?: return ObstacleModelOutput(emptyList())
        val activeTensorBindings = resolvedTensorBindings ?: return ObstacleModelOutput(emptyList())
        val activeInputDataType = inputDataType

        val startTime = SystemClock.uptimeMillis()
        val preprocessBackend = preprocessInput(activeProcessor, frame, activeInputDataType)
        val afterPreprocessTime = SystemClock.uptimeMillis()

        writeInput(activeInputBuffer, activeInputDataType)
        activeSession.run()
        val afterModelTime = SystemClock.uptimeMillis()

        // Zero-copy realtime path: decode straight from the output buffer handle, skipping the
        // multi-MB readFloat()/readInt8() allocation for raw detection outputs. Falls through to
        // the read + array path below if the handle is unavailable or declines.
        if (detectionBufferHandle != 0L &&
            activeTensorBindings.detectionLayout == ObstacleDetectionLayout.RAW_TRANSPOSED
        ) {
            val handleOutput = when (activeTensorBindings.detectionOutputElementType) {
                TfliteTensorElementType.FLOAT32 -> activePostProcessor.postProcessFloatFromHandle(
                    frame = frame,
                    tensorBufferHandle = detectionBufferHandle,
                    rawElementCount = activeTensorBindings.detectionElementCount,
                )
                TfliteTensorElementType.INT8 -> activePostProcessor.postProcessInt8FromHandle(
                    frame = frame,
                    tensorBufferHandle = detectionBufferHandle,
                    rawElementCount = activeTensorBindings.detectionElementCount,
                    quantization = activeTensorBindings.detectionOutputQuantization,
                )
                else -> null
            }
            if (handleOutput != null) {
                val afterPostprocessTime = SystemClock.uptimeMillis()
                if (!hasLoggedTensorInfo) {
                    logService.info(
                        TAG,
                        "obstacle runtime tensors (zero-copy): detection=${activeTensorBindings.detectionOutputTensorName}[idx=${activeTensorBindings.detectionBufferIndex}], detectionValues=${activeTensorBindings.detectionElementCount}, frame=${frame.width}x${frame.height}, rotation=${frame.rotationDegrees}, inputType=$activeInputDataType, accelerator=${activeSession.accelerator}"
                    )
                    hasLoggedTensorInfo = true
                }
                return ObstacleModelOutput(
                    detections = handleOutput.detections,
                    preprocessTimeMs = afterPreprocessTime - startTime,
                    inferenceTimeMs = afterModelTime - afterPreprocessTime,
                    outputReadTimeMs = 0L,
                    postprocessTimeMs = afterPostprocessTime - afterModelTime,
                    runtimeInfo = MlRuntimeInfo(
                        accelerator = activeSession.accelerator.name,
                        acceleratorSelection = activeSession.acceleratorSelection,
                        preprocessBackend = preprocessBackend.traceName,
                        postprocessBackend = handleOutput.backend,
                    ),
                )
            }
        }

        val detectionTensor = readOutputTensor(
            tensorBuffer = activeDetectionBuffer,
            elementType = activeTensorBindings.detectionOutputElementType,
            quantization = activeTensorBindings.detectionOutputQuantization,
            tensorName = activeTensorBindings.detectionOutputTensorName,
        )
        val afterOutputReadTime = SystemClock.uptimeMillis()

        if (!hasLoggedTensorInfo) {
            logService.info(
                TAG,
                "obstacle runtime tensors: detection=${activeTensorBindings.detectionOutputTensorName}[idx=${activeTensorBindings.detectionBufferIndex}], detectionValues=${detectionTensor.size}, frame=${frame.width}x${frame.height}, rotation=${frame.rotationDegrees}, inputType=$activeInputDataType, accelerator=${activeSession.accelerator}"
            )
            hasLoggedTensorInfo = true
        }

        val postProcessOutput = when (detectionTensor) {
            is ObstacleDetectionTensor.FloatTensor -> activePostProcessor.postProcessWithBackend(
                frame = frame,
                rawDetections = detectionTensor.values,
            )

            is ObstacleDetectionTensor.Int8Tensor -> activePostProcessor.postProcessWithBackend(
                frame = frame,
                rawDetections = detectionTensor.values,
                quantization = detectionTensor.quantization,
            )
        }
        val afterPostprocessTime = SystemClock.uptimeMillis()

        return ObstacleModelOutput(
            detections = postProcessOutput.detections,
            preprocessTimeMs = afterPreprocessTime - startTime,
            inferenceTimeMs = afterModelTime - afterPreprocessTime,
            outputReadTimeMs = afterOutputReadTime - afterModelTime,
            postprocessTimeMs = afterPostprocessTime - afterOutputReadTime,
            runtimeInfo = MlRuntimeInfo(
                accelerator = activeSession.accelerator.name,
                acceleratorSelection = activeSession.acceleratorSelection,
                preprocessBackend = preprocessBackend.traceName,
                postprocessBackend = postProcessOutput.backend,
            ),
        )
    }

    override fun release() {
        runBlocking(singleThreadDispatcher) {
            cleanupInternal()
        }
    }

    private fun configurePreAndPostProcessing(metadata: TfliteModelMetadata): ResolvedTensorBindings {
        val inputMetadata = metadata.resolveInputTensor()
        val resolvedInputDataType = resolveModelInputDataType(
            configured = modelConfig.inputDataType,
            tensorElementType = inputMetadata.elementType,
        )
        val inputDimensions = inputMetadata.shape
        val inputSpec = imageTensorSpec(
            dimensions = inputDimensions,
            expectedChannels = 3,
            description = "obstacle input",
        )
        require(inputSpec.width == inputSpec.height) {
            "obstacle post-processor expects square input, got ${inputSpec.width}x${inputSpec.height}"
        }
        val outputSpec = validateOutputTensors(metadata)

        val inputConfig = ModelTensorConfig(
            inputWidth = inputSpec.width,
            inputHeight = inputSpec.height,
            inputLayout = inputSpec.layout,
            outputWidth = inputSpec.width,
            outputHeight = inputSpec.height,
            outputChannels = 1,
            mean = Triple(0f, 0f, 0f),
            std = Triple(1f, 1f, 1f),
            confidenceThreshold = perceptionConfig.minObstacleConfidence,
            resizeFilter = modelConfig.resizeFilter,
        )

        inputDataType = resolvedInputDataType.dataType
        val inputElementCount = inputSpec.width * inputSpec.height * inputSpec.channels
        cachedFloatInput = FloatArray(if (inputDataType == ModelInputDataType.FLOAT32) inputElementCount else 0)
        // INT8 and UINT8 both feed a byte input tensor; only the quantization interpretation differs.
        cachedInt8Input = ByteArray(
            if (inputDataType == ModelInputDataType.INT8 || inputDataType == ModelInputDataType.UINT8) inputElementCount else 0
        )
        processor = ModelInputPreprocessor(
            config = inputConfig,
            inputQuantization = inputMetadata.quantization?.toInputQuantization() ?: modelConfig.inputQuantization,
            preferNativeYuvPreprocessing = modelConfig.preferNativeYuvPreprocessing,
            preprocessCache = preprocessCache,
        )
        postProcessor = ObstaclePostProcessor(
            inputSize = inputSpec.width,
            classCount = modelConfig.classCount,
            detectionLayout = outputSpec.detectionLayout,
            confidenceThreshold = perceptionConfig.minObstacleConfidence,
            maxDetections = perceptionConfig.maxObstacles,
        )

        logService.info(
            TAG,
            "obstacle tensor config: input=${inputSpec.width}x${inputSpec.height}x${inputSpec.channels}:${inputSpec.layout}, output=$outputSpec, inputType=${resolvedInputDataType.dataType}, tensorElement=${resolvedInputDataType.elementTypeName}"
        )

        return ResolvedTensorBindings(
            // Single image input lives at subgraph input slot 0 for all supported models.
            inputBufferIndex = 0,
            detectionBufferIndex = outputSpec.detectionIndex,
            detectionLayout = outputSpec.detectionLayout,
            detectionOutputTensorName = outputSpec.detectionTensorName,
            detectionOutputShape = outputSpec.detectionShapeDescription,
            detectionElementCount = outputSpec.detectionAttributes * outputSpec.detectionCount,
            detectionOutputElementType = outputSpec.detectionElementType,
            detectionOutputQuantization = outputSpec.detectionQuantization,
        )
    }

    private fun validateOutputTensors(metadata: TfliteModelMetadata): ObstacleOutputTensorSpec {
        val detectionTensor = resolveObstacleDetectionOutputTensor(
            metadata = metadata,
            expectedRawAttributes = RAW_BOX_ATTRIBUTES + modelConfig.classCount,
            expectedEndToEndAttributes = END_TO_END_FIXED_ATTRIBUTES,
        )
        val detectionMetadata = detectionTensor.metadata
        val detectionSpec = detectionTensor.spec

        return ObstacleOutputTensorSpec(
            detectionTensorName = detectionMetadata.name,
            detectionIndex = metadata.outputIndexOf(detectionMetadata),
            detectionCount = detectionSpec.detectionCount,
            detectionAttributes = detectionSpec.attributes,
            detectionLayout = detectionSpec.layout,
            detectionShapeDescription = detectionSpec.shapeDescription,
            detectionElementType = detectionMetadata.elementType,
            detectionQuantization = detectionMetadata.quantization,
        )
    }

    private fun resolveObstacleDetectionOutputTensor(
        metadata: TfliteModelMetadata,
        expectedRawAttributes: Int,
        expectedEndToEndAttributes: Int,
    ): ResolvedObstacleDetectionTensor {
        metadata.outputs.forEach { tensor ->
            val spec = ObstacleDetectionTensorSpec.fromOrNull(
                dimensions = tensor.shape,
                expectedRawAttributes = expectedRawAttributes,
                expectedEndToEndAttributes = expectedEndToEndAttributes,
            )
            if (spec != null) {
                return ResolvedObstacleDetectionTensor(metadata = tensor, spec = spec)
            }
        }
        error(
            "Unable to resolve obstacle detection output tensor from " +
                "tensors=${metadata.outputs.map { it.name to it.shape }}"
        )
    }

    private fun readOutputTensor(
        tensorBuffer: TensorBuffer,
        elementType: TfliteTensorElementType,
        quantization: TensorQuantization?,
        tensorName: String,
    ): ObstacleDetectionTensor {
        return when (elementType) {
            TfliteTensorElementType.FLOAT32 -> ObstacleDetectionTensor.FloatTensor(tensorBuffer.readFloat())
            TfliteTensorElementType.INT8 -> ObstacleDetectionTensor.Int8Tensor(
                values = tensorBuffer.readInt8(),
                quantization = quantization,
            )

            else -> error("Unsupported obstacle output tensor '$tensorName' element type: $elementType")
        }
    }

    private fun preprocessInput(
        processor: ModelInputPreprocessor,
        frame: ImageFrame,
        activeInputDataType: ModelInputDataType,
    ): InputPreprocessBackend {
        return when (activeInputDataType) {
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

            ModelInputDataType.UINT8 -> processor.preprocessUint8(
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
            // UINT8 writes the same raw bytes as INT8; the buffer's tensor type decides interpretation.
            ModelInputDataType.INT8,
            ModelInputDataType.UINT8 -> inputBuffer.writeInt8(cachedInt8Input)
            ModelInputDataType.AUTO -> error("AUTO input type must be resolved before detection")
        }
    }

    private fun cleanupInternal() {
        processor?.close()
        processor = null
        cachedFloatInput = FloatArray(0)
        cachedInt8Input = ByteArray(0)
        inputDataType = ModelInputDataType.FLOAT32
        postProcessor = null
        resolvedTensorBindings = null
        detectionBufferHandle = 0L
        inputBuffer = null
        detectionBuffer = null
        session?.close()
        session = null
        hasLoggedTensorInfo = false
        _isInitialized = false
    }

    private sealed interface ObstacleDetectionTensor {
        val size: Int

        data class FloatTensor(
            val values: FloatArray,
        ) : ObstacleDetectionTensor {
            override val size: Int
                get() = values.size
        }

        data class Int8Tensor(
            val values: ByteArray,
            val quantization: TensorQuantization?,
        ) : ObstacleDetectionTensor {
            override val size: Int
                get() = values.size
        }
    }

    private data class ResolvedObstacleDetectionTensor(
        val metadata: TfliteTensorMetadata,
        val spec: ObstacleDetectionTensorSpec,
    )

    private data class ObstacleDetectionTensorSpec(
        val detectionCount: Int,
        val attributes: Int,
        val layout: ObstacleDetectionLayout,
        val shapeDescription: String,
    ) {
        companion object {
            fun fromOrNull(
                dimensions: List<Int>,
                expectedRawAttributes: Int,
                expectedEndToEndAttributes: Int,
            ): ObstacleDetectionTensorSpec? {
                if (dimensions.size != 3 || dimensions[0] != 1) return null
                if (dimensions[1] == expectedRawAttributes) {
                    return ObstacleDetectionTensorSpec(
                        detectionCount = dimensions[2],
                        attributes = dimensions[1],
                        layout = ObstacleDetectionLayout.RAW_TRANSPOSED,
                        shapeDescription = "[1,${dimensions[1]},${dimensions[2]}]",
                    )
                }
                if (dimensions[2] == expectedEndToEndAttributes) {
                    return ObstacleDetectionTensorSpec(
                        detectionCount = dimensions[1],
                        attributes = dimensions[2],
                        layout = ObstacleDetectionLayout.END_TO_END,
                        shapeDescription = "[1,${dimensions[1]},${dimensions[2]}]",
                    )
                }
                return null
            }
        }
    }

    private data class ObstacleOutputTensorSpec(
        val detectionTensorName: String,
        val detectionIndex: Int,
        val detectionCount: Int,
        val detectionAttributes: Int,
        val detectionLayout: ObstacleDetectionLayout,
        val detectionShapeDescription: String,
        val detectionElementType: TfliteTensorElementType,
        val detectionQuantization: TensorQuantization?,
    ) {
        override fun toString(): String {
            return "$detectionTensorName=$detectionShapeDescription:$detectionElementType:$detectionLayout"
        }
    }

    private data class ResolvedTensorBindings(
        val inputBufferIndex: Int,
        val detectionBufferIndex: Int,
        val detectionLayout: ObstacleDetectionLayout,
        val detectionOutputTensorName: String,
        val detectionOutputShape: String,
        val detectionElementCount: Int,
        val detectionOutputElementType: TfliteTensorElementType,
        val detectionOutputQuantization: TensorQuantization?,
    )

    private companion object {
        const val RAW_BOX_ATTRIBUTES = 4
        const val END_TO_END_FIXED_ATTRIBUTES = 6

        // The default obstacle fallback keeps the full-integer NPU/CPU path. GPU can still be
        // requested explicitly; the model source resolver will then choose the GPU-friendly asset.
        val ACCELERATOR_FALLBACK_ORDER = listOf(
            Accelerator.NPU,
            Accelerator.CPU,
        )
    }
}
