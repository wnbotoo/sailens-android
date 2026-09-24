package com.sailens.guidance.semantics

import android.content.Context
import com.sailens.runtime.ModelSource
import com.sailens.runtime.TfliteModelMetadata
import com.sailens.runtime.TfliteModelMetadataReader
import com.sailens.runtime.imageTensorSpecOrNull
import com.sailens.vision.detection.DetectionTensorContract

/**
 * The static check for the obstacle detector, the counterpart of [SemanticModelPreflight].
 *
 * An edition that packages a detector and promises Guidance should find a missing or unreadable
 * detector here, before the user presses start -- not as a failure a few hundred milliseconds into
 * the first session. Same budget as the semantic check (architecture.md §5.2): the TFLite metadata
 * tables are read from a memory-mapped file; nothing is compiled and no delegate is opened.
 *
 * As with the semantic model, a pass means "nothing detectable is wrong", not "the class order is
 * right": a COCO-sized head trained in a different order passes here.
 */
object DetectionModelPreflight {

    fun check(context: Context, source: ModelSource, classCount: Int): Result {
        if (!source.exists(context)) return Result.ModelSourceMissing
        val metadata = runCatching { TfliteModelMetadataReader.read(source.mapBytes(context)) }
            .getOrElse { error ->
                return Result.OutputUnreadable(
                    "cannot parse TFLite metadata from ${source.label}: ${error.message}"
                )
            }
        return checkTensors(metadata, classCount)
    }

    /** The half that needs no Context, so it can be tested without a packaged model. */
    internal fun checkTensors(metadata: TfliteModelMetadata, classCount: Int): Result {
        val input = metadata.inputs.firstNotNullOfOrNull { imageTensorSpecOrNull(it.shape, expectedChannels = 3) }
            ?: return Result.OutputUnreadable(
                "expected a 3-channel image input, got ${metadata.inputs.map { it.name to it.shape }}"
            )
        if (input.width != input.height) {
            return Result.OutputUnreadable("expected a square input, got ${input.width}x${input.height}")
        }
        if (metadata.outputs.none { DetectionTensorContract.outputSpecOrNull(it.shape, classCount) != null }) {
            return Result.OutputUnreadable(
                "expected [1,${DetectionTensorContract.RAW_BOX_ATTRIBUTES + classCount},N] or " +
                    "[1,N,${DetectionTensorContract.END_TO_END_ATTRIBUTES}], got " +
                    "${metadata.outputs.map { it.name to it.shape }}"
            )
        }
        return Result.Compatible
    }

    sealed interface Result {
        /** Nothing detectable is wrong. */
        data object Compatible : Result

        /** No detector where the edition said one would be. */
        data object ModelSourceMissing : Result

        /** The file is there but its tensors are not ones this runtime can bind or decode. */
        data class OutputUnreadable(val detail: String) : Result
    }
}
