package com.friady.sailens.data.source.mapper

import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.perception.ClassMapper

/**
 * Cityscapes 数据集类别映射器
 * 用于 DDRNet 等基于 Cityscapes 训练的模型
 */
class CityscapesClassMapper : ClassMapper {

    override val datasetName: String = "Cityscapes"

    override val classCount: Int = 19

    companion object {
        // Cityscapes 类别 ID
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
        const val UNKNOWN = 255

        // 可通行区域
        private val PASSABLE_IDS = setOf(ROAD, SIDEWALK)

        // 障碍物
        private val OBSTACLE_IDS =
            setOf(
                BUILDING,
                WALL,
                FENCE,
                POLE,
                PERSON,
                RIDER,
                CAR,
                TRUCK,
                BUS,
                TRAIN,
                MOTORCYCLE,
                BICYCLE
            )

        private val FLAT_IDS = setOf(
            ROAD, SIDEWALK
        )

        private val CONSTRUCTION_IDS = setOf(
            BUILDING,
            WALL,
            FENCE,
        )

        private val OBJECT_IDS = setOf(
            POLE,
            TRAFFIC_LIGHT,
            TRAFFIC_SIGN
        )

        private val NATURE_IDS = setOf(
            VEGETATION,
            TERRAIN,
        )

        private val HUMAN_IDS = setOf(
            PERSON,
            RIDER
        )

        private val VEHICLE_IDS = setOf(
            CAR,
            TRUCK,
            BUS,
            TRAIN,
            MOTORCYCLE,
            BICYCLE
        )

        // 类别名称
        private val CLASS_NAMES = arrayOf(
            "road", "sidewalk", "building", "wall", "fence",
            "pole", "traffic_light", "traffic_sign", "vegetation", "terrain",
            "sky", "person", "rider", "car", "truck",
            "bus", "train", "motorcycle", "bicycle"
        )
    }

    override fun isPassable(classId: Int): Boolean {
        return classId in PASSABLE_IDS
    }

    override fun isObstacle(classId: Int): Boolean {
        return classId in OBSTACLE_IDS
    }

    override fun isRoad(classId: Int): Boolean {
        return classId == ROAD
    }

    override fun isTrafficLight(classId: Int): Boolean {
        return classId == TRAFFIC_LIGHT
    }

//    override fun toUnifiedSurfaceType(classId: Int): UnifiedSurfaceType {
//        return when (classId) {
//            SIDEWALK -> UnifiedSurfaceType.WALKABLE
//            ROAD -> UnifiedSurfaceType.ROAD
//            TERRAIN -> UnifiedSurfaceType.TERRAIN
//            in OBSTACLE_IDS -> UnifiedSurfaceType.OBSTACLE
//            else -> UnifiedSurfaceType.BACKGROUND
//        }
//    }

    override fun toGroundType(classId: Int): GroundType {
        return when (classId) {
            ROAD -> GroundType.ROAD
            SIDEWALK -> GroundType.SIDEWALK
            TERRAIN -> GroundType.TERRAIN
            else -> GroundType.UNKNOWN
        }
    }

    override fun toObstacleCategory(classId: Int): ObstacleCategory {
        return when (classId) {
            PERSON, RIDER -> ObstacleCategory.PERSON
            CAR, TRUCK, BUS, TRAIN -> ObstacleCategory.VEHICLE
            MOTORCYCLE, BICYCLE -> ObstacleCategory.BICYCLE
            POLE -> ObstacleCategory.STATIC_OBSTACLE
            else -> ObstacleCategory.UNKNOWN
        }
    }

    override fun getClassName(classId: Int): String {
        return if (classId in 0 until classCount) {
            CLASS_NAMES[classId]
        } else {
            "unknown_$classId"
        }
    }
}

data class CityscapesLabel(
    val trainId: Int,
    val name: String,
    val category: String,
    val color: Triple<Int, Int, Int> // Represents R, G, B
)

private val LABEL_MAP = mapOf(
    0 to CityscapesLabel(0, "road", "flat", Triple(128, 64, 128)),
    1 to CityscapesLabel(1, "sidewalk", "flat", Triple(244, 35, 232)),
    2 to CityscapesLabel(2, "building", "construction", Triple(70, 70, 70)),
    3 to CityscapesLabel(3, "wall", "construction", Triple(102, 102, 156)),
    4 to CityscapesLabel(4, "fence", "construction", Triple(190, 153, 153)),
    5 to CityscapesLabel(5, "pole", "object", Triple(153, 153, 153)),
    6 to CityscapesLabel(6, "traffic light", "object", Triple(250, 170, 30)),
    7 to CityscapesLabel(7, "traffic sign", "object", Triple(220, 220, 0)),
    8 to CityscapesLabel(8, "vegetation", "nature", Triple(107, 142, 35)),
    9 to CityscapesLabel(9, "terrain", "nature", Triple(152, 251, 152)),
    10 to CityscapesLabel(10, "sky", "sky", Triple(70, 130, 180)),
    11 to CityscapesLabel(11, "person", "human", Triple(220, 20, 60)),
    12 to CityscapesLabel(12, "rider", "human", Triple(255, 0, 0)),
    13 to CityscapesLabel(13, "car", "vehicle", Triple(0, 0, 142)),
    14 to CityscapesLabel(14, "truck", "vehicle", Triple(0, 0, 70)),
    15 to CityscapesLabel(15, "bus", "vehicle", Triple(0, 60, 100)),
    16 to CityscapesLabel(16, "train", "vehicle", Triple(0, 80, 100)),
    17 to CityscapesLabel(17, "motorcycle", "vehicle", Triple(0, 0, 230)),
    18 to CityscapesLabel(18, "bicycle", "vehicle", Triple(119, 11, 32))
)