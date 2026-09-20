package com.sailens.data.source.ml.semantic

import android.content.Context
import com.sailens.data.source.ml.CatalogModelSourceResolver
import com.sailens.data.source.ml.ModelSourceResolver
import com.sailens.data.source.ml.ModelType
import com.sailens.data.source.ml.TfliteTensorMetadata
import com.sailens.data.source.ml.TfliteModelMetadataReader
import com.sailens.data.source.ml.ModelTensorConfig
import com.sailens.data.source.ml.InputPreprocessCache
import com.sailens.data.source.ml.imageTensorSpec
import com.sailens.data.source.ml.resolveModelInputDataType
import com.sailens.data.source.ml.session.AcceleratorSelection
import com.sailens.data.source.ml.session.LiteRtSessionFactory
import com.sailens.core.frame.ImageFrame
import com.sailens.domain.model.perception.SegmentationOutput
import com.sailens.core.log.LogService
import com.google.ai.edge.litert.Accelerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "SemanticSegModel"

/**
 * LiteRT semantic segmentation model.
 *
 * 角色：理解可行走区域（哪里能走）。
 * 数据集：Cityscapes 19 类，与现有 domain 分析链路兼容。
 *
 * 加速器选择 + fallback 由 [LiteRtSessionFactory] / AcceleratorSelector 统一处理；本类只负责
 * 读张量元数据、配 pre/post，并把会话交给 [LiteRTSegmenter]。
 */
class LiteRtSemanticSegmentationModel(
    private val context: Context,
    private val modelConfig: SemanticModelConfig = SemanticModelConfig(),
    private val modelSourceResolver: ModelSourceResolver = CatalogModelSourceResolver,
    private val nativeScorePostprocessor: NativeSemanticScorePostprocessor? = null,
    private val preprocessCache: InputPreprocessCache? = null,
    private val logService: LogService,
) : SegmentationModel {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleThreadDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var segmenter: LiteRTSegmenter? = null

    @Volatile
    private var _isInitialized = false

    override val isInitialized: Boolean
        get() = _isInitialized

    override suspend fun initialize() {
        if (_isInitialized) return

        withContext(singleThreadDispatcher) {
            segmenter?.cleanup()
            segmenter = null
            _isInitialized = false

            try {
                val initializedSegmenter = createSegmenter()
                segmenter = initializedSegmenter
                _isInitialized = true
                logService.info(TAG, "Semantic model initialized with ${initializedSegmenter.accelerator}")
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

    override suspend fun segment(frame: ImageFrame): Result<SegmentationOutput> {
        if (!_isInitialized) {
            return Result.failure(IllegalStateException("Segmenter not initialized"))
        }

        return try {
            withContext(singleThreadDispatcher) {
                if (!isActive) {
                    return@withContext Result.failure(CancellationException("Coroutine cancelled"))
                }

                val output = segmenter?.segment(frame)
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

    override suspend fun release() {
        withContext(singleThreadDispatcher) {
            segmenter?.cleanup()
            segmenter = null
            _isInitialized = false
        }
    }

    private fun createSegmenter(): LiteRTSegmenter {
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
            LiteRTSegmenter(
                session = session,
                config = config,
                inputDataType = inputDataType.dataType,
                outputElementType = outputMetadata.elementType,
                outputQuantization = outputMetadata.quantization,
                inputQuantization = inputMetadata.quantization?.toInputQuantization() ?: modelConfig.inputQuantization,
                preferNativeYuvPreprocessing = modelConfig.preferNativeYuvPreprocessing,
                preprocessCache = preprocessCache,
                nativeScorePostprocessor = nativeScorePostprocessor,
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
