package com.friady.sailens.data.source.ml.segmentation

import android.content.Context
import android.util.Log
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.SegmentationOutput
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "DDRNetSegmentationModel"

/**
 * 在 s22 上，推理时间大概110ms-120ms
 */
class DDRNetSegmentationModel(
    private val context: Context,
) : SegmentationModel {
    companion object {
        private const val MODEL_ASSET_PATH = "ddrnet23_slim_cityscapes_float_metadata.tflite"
    }

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

            val config = SegmenterConfig(
                inputWidth = 2048,
                inputHeight = 1024,
                outputWidth = 256,
                outputHeight = 128,
                outputChannels = 19,
                mean = Triple(0f, 0f, 0f),
                std = Triple(1f, 1f, 1f),
                confidenceThreshold = 0.5f
            )

            val initializationResult = runCatching {
                Log.i(TAG, "Initializing DDRNet with GPU accelerator")
                createSegmenter(config, Accelerator.GPU)
            }.recoverCatching { gpuError ->
                Log.w(TAG, "GPU initialization failed, retrying with CPU", gpuError)
                createSegmenter(config, Accelerator.CPU)
            }

            initializationResult
                .onSuccess { initializedSegmenter ->
                    segmenter = initializedSegmenter
                    _isInitialized = true
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to initialize segmenter", error)
                    throw IllegalStateException("Failed to initialize DDRNet segmenter", error)
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

    private fun createSegmenter(
        config: SegmenterConfig,
        accelerator: Accelerator,
    ): LiteRTSegmenter {
        ensureModelAssetAvailable()
        val model = CompiledModel.create(
            context.assets,
            MODEL_ASSET_PATH,
            CompiledModel.Options(accelerator)
        )
        return LiteRTSegmenter(model, config)
    }

    private fun ensureModelAssetAvailable() {
        try {
            context.assets.open(MODEL_ASSET_PATH).use { }
        } catch (error: Exception) {
            throw IllegalStateException(
                "Model asset '$MODEL_ASSET_PATH' is not packaged in app assets. Make sure data/src/main/ml is included as an assets source set.",
                error,
            )
        }
    }
}
