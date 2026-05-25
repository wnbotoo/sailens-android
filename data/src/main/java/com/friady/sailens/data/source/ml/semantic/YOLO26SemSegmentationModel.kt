package com.friady.sailens.data.source.ml.semantic

import android.content.Context
import android.util.Log
import com.friady.sailens.data.source.ml.resolveModelInputDataType
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.SegmentationOutput
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "YOLO26SemModel"

/**
 * YOLO26 semantic segmentation model.
 *
 * 角色：理解可行走区域（哪里能走）。
 * 数据集：Cityscapes 19 类，与现有 domain 分析链路兼容。
 */
class YOLO26SemSegmentationModel(
    private val context: Context,
    private val modelConfig: YOLO26SemModelConfig = YOLO26SemModelConfig(),
    private val nativeSegmentationAnalyzer: NativeSegmentationAnalyzer? = null,
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

            val initializationResult = runCatching {
                Log.i(TAG, "Initializing YOLO26 semantic model with GPU accelerator")
                createSegmenter(Accelerator.GPU)
            }.recoverCatching { gpuError ->
                Log.w(TAG, "GPU initialization failed, retrying with CPU", gpuError)
                createSegmenter(Accelerator.CPU)
            }

            initializationResult
                .onSuccess { initializedSegmenter ->
                    segmenter = initializedSegmenter
                    _isInitialized = true
                    Log.i(
                        TAG,
                        "YOLO26 semantic model initialized with ${initializedSegmenter.accelerator}"
                    )
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to initialize YOLO26 semantic model", error)
                    throw IllegalStateException("Failed to initialize YOLO26 semantic model", error)
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

    private fun createSegmenter(accelerator: Accelerator): LiteRTSegmenter {
        ensureModelAssetAvailable()
        val model = CompiledModel.create(
            context.assets,
            modelConfig.assetPath,
            CompiledModel.Options(accelerator),
        )
        return try {
            val inputTensorType = model.getInputTensorType(modelConfig.inputTensorName)
            val inputDataType = resolveModelInputDataType(
                configured = modelConfig.inputDataType,
                tensorElementType = inputTensorType.elementType,
            )
            val config = model.createSegmenterConfig(inputTensorType)
            Log.i(
                TAG,
                "YOLO26 semantic tensor config: input=${config.inputWidth}x${config.inputHeight}, output=${config.outputWidth}x${config.outputHeight}x${config.outputChannels}, inputType=${inputDataType.dataType}, tensorElement=${inputDataType.elementTypeName}"
            )
            LiteRTSegmenter(
                model = model,
                config = config,
                inputDataType = inputDataType.dataType,
                inputQuantization = modelConfig.inputQuantization,
                preferNativeYuvPreprocessing = modelConfig.preferNativeYuvPreprocessing,
                accelerator = accelerator,
                nativeSegmentationAnalyzer = nativeSegmentationAnalyzer,
            )
        } catch (error: Throwable) {
            model.close()
            throw error
        }
    }

    private fun CompiledModel.createSegmenterConfig(inputTensorType: TensorType): SegmenterConfig {
        val inputDimensions = requireNotNull(inputTensorType.layout) {
            "YOLO26 semantic input tensor '${modelConfig.inputTensorName}' has no layout"
        }.dimensions
        val outputDimensions = requireNotNull(getOutputTensorType(modelConfig.outputTensorName).layout) {
            "YOLO26 semantic output tensor '${modelConfig.outputTensorName}' has no layout"
        }.dimensions
        val inputSpec = NhwcImageTensorSpec.fromInput(inputDimensions)
        val outputSpec = NhwcImageTensorSpec.fromOutput(outputDimensions, modelConfig.outputChannels)

        return SegmenterConfig(
            inputWidth = inputSpec.width,
            inputHeight = inputSpec.height,
            outputWidth = outputSpec.width,
            outputHeight = outputSpec.height,
            outputChannels = outputSpec.channels,
            mean = Triple(0f, 0f, 0f),
            std = Triple(1f, 1f, 1f),
            confidenceThreshold = 0.5f,
        )
    }

    private data class NhwcImageTensorSpec(
        val width: Int,
        val height: Int,
        val channels: Int,
    ) {
        companion object {
            fun fromInput(dimensions: List<Int>): NhwcImageTensorSpec {
                require(dimensions.size == 4 && dimensions.last() == 3) {
                    "YOLO26 semantic input must be NHWC RGB, got dimensions=$dimensions"
                }
                return NhwcImageTensorSpec(
                    width = dimensions[2],
                    height = dimensions[1],
                    channels = dimensions[3],
                )
            }

            fun fromOutput(
                dimensions: List<Int>,
                expectedChannels: Int,
            ): NhwcImageTensorSpec {
                require(dimensions.size == 4 && dimensions.last() == expectedChannels) {
                    "YOLO26 semantic output must be NHWC with $expectedChannels classes, got dimensions=$dimensions"
                }
                return NhwcImageTensorSpec(
                    width = dimensions[2],
                    height = dimensions[1],
                    channels = dimensions[3],
                )
            }
        }
    }

    private fun ensureModelAssetAvailable() {
        try {
            context.assets.open(modelConfig.assetPath).use { }
        } catch (error: Exception) {
            throw IllegalStateException(
                "Model asset '${modelConfig.assetPath}' is not packaged in app assets.",
                error,
            )
        }
    }
}
