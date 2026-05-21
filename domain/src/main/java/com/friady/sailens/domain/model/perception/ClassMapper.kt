package com.friady.sailens.domain.model.perception

import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.model.common.ObstacleCategory

/**
 * 类别映射器接口
 * 将模型输出的类别 ID 映射到领域类型
 */
interface ClassMapper {

    /**
     * 数据集名称
     */
    val datasetName: String

    /**
     * 类别总数
     */
    val classCount: Int

    /**
     * 判断是否为可通行区域
     */
    fun isPassable(classId: Int): Boolean

    /**
     * 判断是否为障碍物
     */
    fun isObstacle(classId: Int): Boolean

    /**
     * 判断是否为道路
     */
    fun isRoad(classId: Int): Boolean

    /**
     * 判断是否为交通灯
     */
    fun isTrafficLight(classId: Int): Boolean

    /**
     * 映射到细分地面类型
     */
    fun toGroundType(classId: Int): GroundType

    /**
     * 映射到障碍物类别
     */
    fun toObstacleCategory(classId: Int): ObstacleCategory

    /**
     * 获取类别名称（用于调试）
     */
    fun getClassName(classId: Int): String
}
