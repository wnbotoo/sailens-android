package com.sailens.vision.depth

import android.content.Context
import com.sailens.runtime.ModelSource
import com.sailens.runtime.TfliteModelMetadata
import com.sailens.runtime.TfliteModelMetadataReader

/**
 * The depth model's static check: is there a file, and do its tensors fit the `depth` contract
 * ([DepthTensorContract])? Same budget as the other preflights (architecture.md §5.2): the TFLite
 * metadata tables are read from a memory-mapped file; nothing is compiled and no delegate is opened.
 *
 * A pass means the shapes fit, not that the output is disparity: a model that outputs depth passes
 * here, and the ground fit then finds it inverted on every frame. The source to check is the one the
 * runner will load: the host asks its own `ModelSourceResolver` for it.
 */
object DepthModelPreflight {

    fun check(context: Context, source: ModelSource): Result {
        if (!source.exists(context)) return Result.ModelSourceMissing
        val metadata = runCatching { TfliteModelMetadataReader.read(source.mapBytes(context)) }
            .getOrElse { error ->
                return Result.InputUnreadable("cannot parse TFLite metadata from ${source.label}: ${error.message}")
            }
        return checkTensors(metadata)
    }

    /** The half that needs no Context, so it can be tested without a packaged model. */
    fun checkTensors(metadata: TfliteModelMetadata): Result = when (val resolved = DepthTensorContract.resolve(metadata)) {
        is DepthTensorContract.Resolution.Compatible -> Result.Compatible(resolved.spec)
        is DepthTensorContract.Resolution.InputUnreadable -> Result.InputUnreadable(resolved.detail)
        is DepthTensorContract.Resolution.OutputUnreadable -> Result.OutputUnreadable(resolved.detail)
        is DepthTensorContract.Resolution.OutputSizeMismatch -> Result.OutputSizeMismatch(resolved.detail)
    }

    sealed interface Result {
        /** Nothing detectable is wrong. */
        data class Compatible(val spec: DepthTensorSpec) : Result

        /** No depth model where the host looks for one: depth is not configured. */
        data object ModelSourceMissing : Result

        data class InputUnreadable(val detail: String) : Result
        data class OutputUnreadable(val detail: String) : Result

        /** The output is not the input's size; this version of the contract does not map it. */
        data class OutputSizeMismatch(val detail: String) : Result
    }
}
