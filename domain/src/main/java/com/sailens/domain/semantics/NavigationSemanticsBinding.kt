package com.sailens.domain.semantics

import com.sailens.vision.taxonomy.Taxonomy

/**
 * The static half of the taxonomy check from architecture.md §6.2.
 *
 * Verifies that the semantics Guidance is about to apply were written for the taxonomy the model
 * actually declares. This is cheap and runs without initialising a model or an accelerator, so it
 * belongs in preflight rather than at session start.
 *
 * **What this cannot do:** it cannot confirm that output channel 11 really is `person`. The models
 * carry no labels, so channel order remains a manual release gate. A model with the right id and
 * the right class count can still be trained in a different order and would pass here -- see
 * docs/models.md. Do not read a pass as "the semantics are correct".
 */
object NavigationSemanticsBinding {

    fun validate(taxonomy: Taxonomy, semantics: NavigationSemantics): Result {
        if (taxonomy.id != semantics.taxonomyId) {
            return Result.TaxonomyMismatch(
                declared = taxonomy.id.value,
                expected = semantics.taxonomyId.value,
            )
        }
        if (taxonomy.classCount != semantics.classCount) {
            return Result.ClassCountMismatch(
                declared = taxonomy.classCount,
                expected = semantics.classCount,
            )
        }
        return Result.Compatible
    }

    sealed interface Result {
        data object Compatible : Result

        data class TaxonomyMismatch(val declared: String, val expected: String) : Result

        data class ClassCountMismatch(val declared: Int, val expected: Int) : Result
    }
}
