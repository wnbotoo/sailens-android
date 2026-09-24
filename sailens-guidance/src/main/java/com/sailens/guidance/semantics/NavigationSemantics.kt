package com.sailens.guidance.semantics

import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.vision.taxonomy.TaxonomyId

/**
 * The navigation judgements Guidance makes about a taxonomy's classes: what can be walked on,
 * what is in the way, what a class means for someone who cannot see it.
 *
 * This is the half of the old ClassMapper that is *not* a dataset fact (architecture.md §6.2).
 * A different product could read the same taxonomy and reach different conclusions; those
 * conclusions belong here, not in the vision module.
 *
 * [taxonomyId] and [classCount] are what preflight checks against the taxonomy a model declares
 * (see [NavigationSemanticsBinding]). They cannot prove the model's channels *mean* what this
 * says they mean -- with no labels in the model metadata, channel order stays a manual release
 * gate, and a wrong-order model will run happily while describing the scene incorrectly to
 * someone who has no way to notice.
 */
interface NavigationSemantics {

    val taxonomyId: TaxonomyId

    val classCount: Int

    fun isPassable(classId: Int): Boolean

    fun isObstacle(classId: Int): Boolean

    fun isRoad(classId: Int): Boolean

    fun isTrafficLight(classId: Int): Boolean

    /**
     * A vertical structure that closes a path — a wall, a fence — as opposed to something standing
     * on the ground ([isObstacle]). Kept apart from [isObstacle] because barriers are not tracked or
     * announced as objects; the connectivity analysis already reads them as "not passable". What
     * this adds is the knowledge that the model *recognised* it, so a barrier filling the bottom of
     * the frame is not mistaken for ground the model cannot name (see GroundRecognitionAnalyzer).
     */
    fun isBarrier(classId: Int): Boolean = false

    fun toGroundType(classId: Int): GroundType

    fun toObstacleCategory(classId: Int): ObstacleCategory
}

/**
 * Supplies the semantics for each pipeline's model. Null detection semantics means the edition
 * runs semantic segmentation only.
 */
interface NavigationSemanticsProvider {

    fun semanticSegmentationSemantics(): NavigationSemantics

    fun detectionSemantics(): NavigationSemantics?
}
