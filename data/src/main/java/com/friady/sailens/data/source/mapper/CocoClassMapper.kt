package com.friady.sailens.data.source.mapper

import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.perception.ClassMapper

/**
 * COCO 数据集类别映射器
 * 用于 YOLO 等基于 COCO 训练的实例分割模型
 */
class CocoClassMapper : ClassMapper {

    override val datasetName: String = "COCO"

    override val classCount: Int = 80

    companion object {
        // COCO 类别 ID
        const val PERSON = 0
        const val BICYCLE = 1
        const val CAR = 2
        const val MOTORCYCLE = 3
        const val BUS = 5
        const val TRUCK = 7
        const val TRAFFIC_LIGHT = 9
        const val STOP_SIGN = 11
        const val BENCH = 13
        const val DOG = 16
        const val CAT = 15
        const val CHAIR = 56
        const val POTTED_PLANT = 58

        // 车辆
        private val VEHICLE_IDS = setOf(CAR, BUS, TRUCK)

        // 两轮车
        private val BICYCLE_IDS = setOf(BICYCLE, MOTORCYCLE)

        // 人员
        private val PERSON_IDS = setOf(PERSON)

        // 静态障碍物
        private val STATIC_OBSTACLE_IDS = setOf(BENCH, CHAIR, POTTED_PLANT, STOP_SIGN)
    }

    override fun isPassable(classId: Int): Boolean {
        // COCO 主要用于物体检测，不包含地面类别
        return false
    }

    override fun isObstacle(classId: Int): Boolean {
        return classId in PERSON_IDS ||
                classId in VEHICLE_IDS ||
                classId in BICYCLE_IDS ||
                classId in STATIC_OBSTACLE_IDS
    }

    override fun isRoad(classId: Int): Boolean {
        // COCO 不包含道路类别
        return false
    }

    override fun isTrafficLight(classId: Int): Boolean {
        return classId == TRAFFIC_LIGHT
    }

    override fun toGroundType(classId: Int): GroundType {
        // COCO 不包含地面类别
        return GroundType.UNKNOWN
    }

    override fun toObstacleCategory(classId: Int): ObstacleCategory {
        return when {
            classId in PERSON_IDS -> ObstacleCategory.PERSON
            classId in VEHICLE_IDS -> ObstacleCategory.VEHICLE
            classId in BICYCLE_IDS -> ObstacleCategory.BICYCLE
            classId in STATIC_OBSTACLE_IDS -> ObstacleCategory.STATIC_OBSTACLE
            else -> ObstacleCategory.UNKNOWN
        }
    }

    override fun getClassName(classId: Int): String {
        return when (classId) {
            PERSON -> "person"
            BICYCLE -> "bicycle"
            CAR -> "car"
            MOTORCYCLE -> "motorcycle"
            BUS -> "bus"
            TRUCK -> "truck"
            TRAFFIC_LIGHT -> "traffic_light"
            else -> "class_$classId"
        }
    }
}
