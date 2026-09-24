package com.sailens.guidance.semantics

import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.vision.taxonomy.CityscapesTaxonomy
import com.sailens.vision.taxonomy.TaxonomyId

/** Guidance's reading of the Cityscapes 19-class taxonomy. */
object CityscapesNavigationSemantics : NavigationSemantics {

    override val taxonomyId: TaxonomyId = CityscapesTaxonomy.id

    override val classCount: Int = CityscapesTaxonomy.classCount

    override fun isPassable(classId: Int): Boolean = classId in PASSABLE_IDS

    override fun isObstacle(classId: Int): Boolean = classId in OBSTACLE_IDS

    override fun isRoad(classId: Int): Boolean = classId == CityscapesTaxonomy.ROAD

    override fun isTrafficLight(classId: Int): Boolean = classId == CityscapesTaxonomy.TRAFFIC_LIGHT

    // `building` is deliberately not a barrier: this model also reads a near indoor floor as
    // building, so "building fills the bottom" is ambiguous between a facade and an unknown floor.
    override fun isBarrier(classId: Int): Boolean =
        classId == CityscapesTaxonomy.WALL || classId == CityscapesTaxonomy.FENCE

    override fun toGroundType(classId: Int): GroundType = when (classId) {
        CityscapesTaxonomy.ROAD -> GroundType.ROAD
        CityscapesTaxonomy.SIDEWALK -> GroundType.SIDEWALK
        CityscapesTaxonomy.TERRAIN -> GroundType.TERRAIN
        else -> GroundType.UNKNOWN
    }

    override fun toObstacleCategory(classId: Int): ObstacleCategory = when (classId) {
        CityscapesTaxonomy.PERSON, CityscapesTaxonomy.RIDER -> ObstacleCategory.PERSON
        CityscapesTaxonomy.CAR,
        CityscapesTaxonomy.TRUCK,
        CityscapesTaxonomy.BUS,
        CityscapesTaxonomy.TRAIN,
        -> ObstacleCategory.VEHICLE

        CityscapesTaxonomy.MOTORCYCLE, CityscapesTaxonomy.BICYCLE -> ObstacleCategory.BICYCLE
        CityscapesTaxonomy.POLE -> ObstacleCategory.STATIC_OBSTACLE
        else -> ObstacleCategory.UNKNOWN
    }

    private val PASSABLE_IDS = setOf(CityscapesTaxonomy.ROAD, CityscapesTaxonomy.SIDEWALK)

    private val OBSTACLE_IDS = setOf(
        CityscapesTaxonomy.POLE,
        CityscapesTaxonomy.PERSON,
        CityscapesTaxonomy.RIDER,
        CityscapesTaxonomy.CAR,
        CityscapesTaxonomy.TRUCK,
        CityscapesTaxonomy.BUS,
        CityscapesTaxonomy.TRAIN,
        CityscapesTaxonomy.MOTORCYCLE,
        CityscapesTaxonomy.BICYCLE,
    )
}
