package com.sailens.domain.semantics

import com.sailens.domain.model.common.GroundType
import com.sailens.domain.model.common.ObstacleCategory
import com.sailens.vision.taxonomy.CocoTaxonomy
import com.sailens.vision.taxonomy.TaxonomyId

/**
 * Guidance's reading of the COCO detection taxonomy.
 *
 * COCO has no ground classes at all, so nothing here is passable and nothing is road; the semantic
 * model answers those questions. This exists to turn detected objects into obstacle categories.
 */
object CocoNavigationSemantics : NavigationSemantics {

    override val taxonomyId: TaxonomyId = CocoTaxonomy.id

    override val classCount: Int = CocoTaxonomy.classCount

    override fun isPassable(classId: Int): Boolean = false

    override fun isObstacle(classId: Int): Boolean =
        classId in PERSON_IDS ||
            classId in VEHICLE_IDS ||
            classId in BICYCLE_IDS ||
            classId in STATIC_OBSTACLE_IDS

    override fun isRoad(classId: Int): Boolean = false

    override fun isTrafficLight(classId: Int): Boolean = classId == CocoTaxonomy.TRAFFIC_LIGHT

    override fun toGroundType(classId: Int): GroundType = GroundType.UNKNOWN

    override fun toObstacleCategory(classId: Int): ObstacleCategory = when {
        classId in PERSON_IDS -> ObstacleCategory.PERSON
        classId in VEHICLE_IDS -> ObstacleCategory.VEHICLE
        classId in BICYCLE_IDS -> ObstacleCategory.BICYCLE
        classId in STATIC_OBSTACLE_IDS -> ObstacleCategory.STATIC_OBSTACLE
        else -> ObstacleCategory.UNKNOWN
    }

    private val PERSON_IDS = setOf(CocoTaxonomy.PERSON)

    private val VEHICLE_IDS = setOf(CocoTaxonomy.CAR, CocoTaxonomy.BUS, CocoTaxonomy.TRUCK)

    private val BICYCLE_IDS = setOf(CocoTaxonomy.BICYCLE, CocoTaxonomy.MOTORCYCLE)

    private val STATIC_OBSTACLE_IDS = setOf(
        CocoTaxonomy.BENCH,
        CocoTaxonomy.CHAIR,
        CocoTaxonomy.POTTED_PLANT,
        CocoTaxonomy.STOP_SIGN,
    )
}
