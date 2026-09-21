package com.sailens.vision.semantic

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.sailens.core.frame.ImageFrame
import com.sailens.core.log.LogService
import com.sailens.runtime.CatalogModelSourceResolver
import com.sailens.runtime.InputPreprocessCache
import com.sailens.runtime.ModelSourceResolver
import com.sailens.runtime.ModelTensorConfig
import com.sailens.runtime.ModelType
import com.sailens.runtime.TfliteModelMetadataReader
import com.sailens.runtime.TfliteTensorMetadata
import com.sailens.runtime.imageTensorSpec
import com.sailens.runtime.resolveModelInputDataType
import com.sailens.runtime.session.AcceleratorSelection
import com.sailens.runtime.session.LiteRtSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "SemanticSegModel"

/**
 * LiteRT semantic segmentation: owns the session lifecycle and the tensor configuration.
 *
 * 角色：理解可行走区域（哪里能走）。
 *
 * 加速器选择 + fallback 由 [LiteRtSessionFactory] / AcceleratorSelector 统一处理；本类只负责
 * 读张量元数据、配 pre/post，并把会话交给 [SegmentationRunner]。
 *
 * Generic in its result: what a class id means is the caller's [SemanticPostprocessor], not this
 * class's business (architecture.md 6.3, 6.5).
 */
class LiteRtSemanticSegmenter<R>(
    private val context: Context,
    private val postprocessor: SemanticPostprocessor<R>,
    private val modelConfig: SemanticModelConfig = SemanticModelConfig(),
    private val modelSourceResolver: ModelSourceResolver = CatalogModelSourceResolver,
    private val preprocessCache: InputPreprocessCache? = null,
    private val logService: LogService,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleThreadDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var runner: SegmentationRunner<R>? = null

    @Volatile
    private var _isInitialized = false

    val isInitialized: Boolean
        get() = _isInitialized

    suspend fun initialize() {
        if (_isInitialized) return

        withContext(singleThreadDispatcher) {
            runner?.cleanup()
            runner = null
            _isInitialized = false

            try {
                val initializedRunner = createRunner()
                runner = initializedRunner
                _isInitialized = true
                logService.info(TAG, "Semantic model initialized with ${initializedRunner.accelerator}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logService.error(TAG, "Failed to initialize semantic model", error)
                throw IllegalStateException("Failed to initialize semantic model", error)
            } catch (error: UnsatisfiedLinkError) {
                logService.error(TAG, "Failed to initialize semantic model", error)
                throw IllegalStateException("Failed to initialize semantic model", error)
            }
        }
    }

    suspend fun segment(frame: ImageFrame): Result<SegmentationRunResult<R>> {
        if (!_isInitialized) {
            return Result.failure(IllegalStateException("Segmenter not initialized"))
        }

        return try {
            withContext(singleThreadDispatcher) {
                if (!isActive) {
                    return@withContext Result.failure(CancellationException("Coroutine cancelled"))
                }

                val output = runner?.run(frame)
                if (output != null) {
                    Result.success(output)
                } else {
                    Result.failure(RuntimeException("Segmentation returned null"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun release() {
        withContext(singleThreadDispatcher) {
            runner?.cleanup()
            runner = null
            _isInitialized = false
        }
    }

    private fun createRunner(): SegmentationRunner<R> {
        val session = LiteRtSessionFactory.create(
            context = context,
            sourceResolver = { accelerator ->
                modelSourceResolver.source(ModelType.SEMANTIC_SEGMENTATION, accelerator)
            },
            selection = AcceleratorSelection(
                mode = modelConfig.acceleratorSelectionMode,
                preferredBackend = modelConfig.acceleratorBackend,
                fallbackOrder = ACCELERATOR_FALLBACK_ORDER,
            ),
            logTag = TAG,
            modelLabel = "semantic model",
            logService = logService,
        )
        return try {
            val metadata = TfliteModelMetadataReader.read(context, session.source)
            val inputMetadata = metadata.resolveInputTensor()
            val outputMetadata = metadata.resolveSemanticOutputTensor(
                outputChannels = modelConfig.outputChannels,
            )
            val inputDataType = resolveModelInputDataType(
                configured = modelConfig.inputDataType,
                tensorElementType = inputMetadata.elementType,
            )
            val config = createModelTensorConfig(
                inputMetadata = inputMetadata,
                outputMetadata = outputMetadata,
            )
            logService.info(
                TAG,
                "semantic tensor config: source=${session.source.label}, input=${inputMetadata.name}:${config.inputWidth}x${config.inputHeight}:${config.inputLayout}, output=${outputMetadata.name}:${config.outputWidth}x${config.outputHeight}x${config.outputChannels}:${config.outputLayout}, inputType=${inputDataType.dataType}, tensorElement=${inputDataType.elementTypeName}"
            )
            SegmentationRunner(
                session = session,
                config = config,
                inputDataType = inputDataType.dataType,
                outputElementType = outputMetadata.elementType,
                outputQuantization = outputMetadata.quantization,
                inputQuantization = inputMetadata.quantization?.toInputQuantization() ?: modelConfig.inputQuantization,
                preferNativeYuvPreprocessing = modelConfig.preferNativeYuvPreprocessing,
                preprocessCache = preprocessCache,
                postprocessor = postprocessor,
            )
        } catch (error: CancellationException) {
            session.close()
            throw error
        } catch (error: Exception) {
            session.close()
            throw error
        } catch (error: UnsatisfiedLinkError) {
            session.close()
            throw error
        }
    }

    private fun createModelTensorConfig(
        inputMetadata: TfliteTensorMetadata,
        outputMetadata: TfliteTensorMetadata,
    ): ModelTensorConfig {
        val inputDimensions = inputMetadata.shape
        val outputDimensions = outputMetadata.shape
        val inputSpec = imageTensorSpec(
            dimensions = inputDimensions,
            expectedChannels = 3,
            description = "semantic input",
        )
        val outputSpec = imageTensorSpec(
            dimensions = outputDimensions,
            expectedChannels = modelConfig.outputChannels,
            description = "semantic output",
        )

        return ModelTensorConfig(
            inputWidth = inputSpec.width,
            inputHeight = inputSpec.height,
            inputLayout = inputSpec.layout,
            outputWidth = outputSpec.width,
            outputHeight = outputSpec.height,
            outputChannels = outputSpec.channels,
            outputLayout = outputSpec.layout,
            mean = Triple(0f, 0f, 0f),
            std = Triple(1f, 1f, 1f),
            confidenceThreshold = 0.5f,
            resizeFilter = modelConfig.resizeFilter,
        )
    }

    private companion object {
        val ACCELERATOR_FALLBACK_ORDER = listOf(
            Accelerator.NPU,
            Accelerator.GPU,
            Accelerator.CPU,
        )
    }
}
