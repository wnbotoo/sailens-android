package com.friady.sailens.domain.model.perception

import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.BottomStats
import com.friady.sailens.domain.model.common.GroundType

/**
 * 语义分割分析结果（纯数据类）
 */
data class SegmentationAnalysis(
    val passableMask: BinaryMask,
    val obstacleMask: BinaryMask,
    val roadRatio: Float,
    val hasTrafficLight: Boolean,
    val bottomCenterGroundDistribution: Map<GroundType, Float>,
    val bottomCenterRoadRatio: Float,
    val bottomStats: BottomStats,
    val passablePixelCount: Int,
    val navigationPassableRatio: Float,
    val obstaclePixelCount: Int,
    val dominantClassNames: List<String>,
    val segmentation: SegmentationMask,  // 暴露给需要的处理器
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SegmentationAnalysis) return false
        return width == other.width && height == other.height
    }

    override fun hashCode(): Int = 31 * width + height
}