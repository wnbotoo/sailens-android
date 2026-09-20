package com.sailens.vision.taxonomy

/** The 80 COCO detection classes. Only the ones Sailens has ever needed a name for are named. */
object CocoTaxonomy : Taxonomy {

    override val id: TaxonomyId = TaxonomyId("coco-80")

    override val classCount: Int = 80

    override fun label(classId: Int): String = LABELS[classId] ?: "class_$classId"

    const val PERSON = 0
    const val BICYCLE = 1
    const val CAR = 2
    const val MOTORCYCLE = 3
    const val BUS = 5
    const val TRUCK = 7
    const val TRAFFIC_LIGHT = 9
    const val STOP_SIGN = 11
    const val BENCH = 13
    const val CAT = 15
    const val DOG = 16
    const val CHAIR = 56
    const val POTTED_PLANT = 58

    private val LABELS = mapOf(
        PERSON to "person",
        BICYCLE to "bicycle",
        CAR to "car",
        MOTORCYCLE to "motorcycle",
        BUS to "bus",
        TRUCK to "truck",
        TRAFFIC_LIGHT to "traffic_light",
        STOP_SIGN to "stop_sign",
        BENCH to "bench",
        CHAIR to "chair",
        POTTED_PLANT to "potted_plant",
    )
}
