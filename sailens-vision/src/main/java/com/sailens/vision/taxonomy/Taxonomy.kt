package com.sailens.vision.taxonomy

/**
 * Identifies a dataset's class definition, so that a model and the semantics read off it can be
 * checked against each other (architecture.md §6.2).
 */
@JvmInline
value class TaxonomyId(val value: String)

/**
 * What a dataset says about itself: how many classes it has, what they are called.
 *
 * Dataset **facts** only. Whether class 11 is something you can walk on, or something that should
 * make the app warn you, is a navigation judgement and lives in NavigationSemantics instead --
 * that separation is the whole point of §6.2, because the same taxonomy can serve products that
 * make different judgements about it.
 */
interface Taxonomy {
    val id: TaxonomyId

    val classCount: Int

    /** A human-readable label for debugging and traces, never a routing decision. */
    fun label(classId: Int): String
}
