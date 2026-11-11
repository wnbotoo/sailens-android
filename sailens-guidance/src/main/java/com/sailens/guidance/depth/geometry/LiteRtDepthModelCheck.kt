package com.sailens.guidance.depth.geometry

import android.content.Context
import com.sailens.runtime.ModelSourceResolver
import com.sailens.runtime.ModelType
import com.sailens.runtime.source
import com.sailens.vision.depth.DepthModelConfig
import com.sailens.vision.depth.DepthModelPreflight

/**
 * The static check of the depth model: the file the host's resolver names for the configured accelerator,
 * the same one `LiteRtDepthRunner` loads. Reads the TFLite tables only; compiles nothing.
 */
class LiteRtDepthModelCheck(
    private val context: Context,
    private val modelConfig: DepthModelConfig,
    private val modelSourceResolver: ModelSourceResolver,
) : DepthModelCheck {
    override fun check(): DepthModelCheckResult = resultOf(
        DepthModelPreflight.check(context, modelSourceResolver.source(ModelType.DEPTH_ESTIMATION, modelConfig.acceleratorBackend)),
    )

    companion object {
        fun resultOf(result: DepthModelPreflight.Result): DepthModelCheckResult = when (result) {
            is DepthModelPreflight.Result.Compatible -> DepthModelCheckResult.Compatible
            DepthModelPreflight.Result.ModelSourceMissing -> DepthModelCheckResult.Missing
            is DepthModelPreflight.Result.InputUnreadable -> DepthModelCheckResult.Unreadable("input_unreadable: ${result.detail}")
            is DepthModelPreflight.Result.OutputUnreadable -> DepthModelCheckResult.Unreadable("output_unreadable: ${result.detail}")
            is DepthModelPreflight.Result.OutputSizeMismatch -> DepthModelCheckResult.Unreadable("output_size_mismatch: ${result.detail}")
        }
    }
}
