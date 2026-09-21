package com.sailens.vision.taxonomy

/** The 19 training classes of Cityscapes, in the order the semantic models emit them. */
object CityscapesTaxonomy : Taxonomy {

    override val id: TaxonomyId = TaxonomyId("cityscapes-19")

    override val classCount: Int = 19

    override fun label(classId: Int): String =
        if (classId in CLASS_NAMES.indices) CLASS_NAMES[classId] else "unknown_$classId"

    const val ROAD = 0
    const val SIDEWALK = 1
    const val BUILDING = 2
    const val WALL = 3
    const val FENCE = 4
    const val POLE = 5
    const val TRAFFIC_LIGHT = 6
    const val TRAFFIC_SIGN = 7
    const val VEGETATION = 8
    const val TERRAIN = 9
    const val SKY = 10
    const val PERSON = 11
    const val RIDER = 12
    const val CAR = 13
    const val TRUCK = 14
    const val BUS = 15
    const val TRAIN = 16
    const val MOTORCYCLE = 17
    const val BICYCLE = 18

    private val CLASS_NAMES = arrayOf(
        "road", "sidewalk", "building", "wall", "fence",
        "pole", "traffic_light", "traffic_sign", "vegetation", "terrain",
        "sky", "person", "rider", "car", "truck",
        "bus", "train", "motorcycle", "bicycle",
    )
}
