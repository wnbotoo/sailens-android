package com.sailens.guidance.semantics

import android.content.Context
import com.sailens.runtime.ModelSource
import com.sailens.runtime.TfliteModelMetadata
import com.sailens.runtime.TfliteModelMetadataReader
import com.sailens.runtime.TfliteTensorMetadata
import com.sailens.vision.taxonomy.Taxonomy

/**
 * What can honestly be checked about a semantic model before anything is loaded.
 *
 * Preflight decides whether a navigation control should exist at all, so it has to answer before
 * the user has asked for anything. That rules out a compiled model, a GPU/NPU delegate or a probe
 * inference (architecture.md §5.2) — but it does not rule out *reading the file*. The tensor shapes
 * sit in the TFLite flatbuffer tables, and parsing those is a few hundred bytes of a memory-mapped
 * file. "The asset exists" was never a real check: an asset of the wrong shape passes it and then
 * fails at session start, after the person has already pressed start and started walking.
 *
 * **What this still cannot do.** It cannot confirm that output channel 11 really is `person`. The
 * models carry no labels, so channel *order* remains a manual release gate (§6.2, docs/models.md).
 * A model with the right id and the right number of classes can be trained in a different order
 * and passes here. Do not read [Result.Compatible] as "the semantics are correct" — read it as
 * "nothing detectable is wrong".
 */
object SemanticModelPreflight {

    fun check(
        context: Context,
        source: ModelSource,
        taxonomy: Taxonomy,
        semantics: NavigationSemantics,
    ): Result {
        val binding = NavigationSemanticsBinding.validate(taxonomy, semantics)
        if (binding != NavigationSemanticsBinding.Result.Compatible) {
            return Result.SemanticsMismatch(binding)
        }

        if (!source.exists(context)) return Result.ModelSourceMissing

        val metadata = runCatching { TfliteModelMetadataReader.read(source.mapBytes(context)) }
            .getOrElse { error ->
                return Result.OutputUnreadable(
                    "cannot parse TFLite metadata from ${source.label}: ${error.message}"
                )
            }

        return checkOutputTensor(metadata, taxonomy)
    }

    /** The half that needs no Context, so it can be tested without a packaged model. */
    internal fun checkOutputTensor(metadata: TfliteModelMetadata, taxonomy: Taxonomy): Result {
        val output = metadata.singleImageOutputOrNull()
            ?: return Result.OutputUnreadable(
                "expected one 4-D output tensor, got ${metadata.outputs.map { it.name to it.shape }}"
            )

        val shape = output.shape
        // Layout is resolved by matching the class count against either end, exactly as the runner
        // does. When neither end matches, the model is not the one these semantics were written
        // for and there is nothing to guess between.
        val nhwcChannels = shape[3]
        val nchwChannels = shape[1]
        if (nhwcChannels != taxonomy.classCount && nchwChannels != taxonomy.classCount) {
            return Result.ClassCountMismatch(
                declared = taxonomy.classCount,
                candidates = listOf(nchwChannels, nhwcChannels),
                tensorName = output.name,
            )
        }
        return Result.Compatible
    }

    private fun TfliteModelMetadata.singleImageOutputOrNull(): TfliteTensorMetadata? =
        outputs.singleOrNull()?.takeIf { it.shape.size == 4 && it.shape[0] == 1 }

    sealed interface Result {
        /** Nothing detectable is wrong. Not the same as "correct" — see the class note. */
        data object Compatible : Result

        /** No model where the edition said one would be. Usually: a build that ships no weights. */
        data object ModelSourceMissing : Result

        /** The declared taxonomy and the navigation semantics disagree before the model is opened. */
        data class SemanticsMismatch(val binding: NavigationSemanticsBinding.Result) : Result

        /** The file is there but its output tensor is not a shape this pipeline can read. */
        data class OutputUnreadable(val detail: String) : Result

        /**
         * The model's output has a different number of classes than the taxonomy declares.
         *
         * @param candidates the channel counts at each end of the output shape, so a log says what
         *   the model actually offered rather than only what was wanted.
         */
        data class ClassCountMismatch(
            val declared: Int,
            val candidates: List<Int>,
            val tensorName: String,
        ) : Result
    }
}
