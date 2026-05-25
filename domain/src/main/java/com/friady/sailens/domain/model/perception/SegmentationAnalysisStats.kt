package com.friady.sailens.domain.model.perception

import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.BottomStats
import com.friady.sailens.domain.model.common.GroundType

/**
 * Stateless per-frame semantic segmentation statistics.
 *
 * Smoothers and debouncers are intentionally applied later by SegmentationAnalyzer,
 * so JVM and native extractors can share the same domain behavior.
 */
data class SegmentationAnalysisStats(
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
    val classCounts: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SegmentationAnalysisStats) return false

        return passableMask == other.passableMask &&
            obstacleMask == other.obstacleMask &&
            roadRatio == other.roadRatio &&
            hasTrafficLight == other.hasTrafficLight &&
            bottomCenterGroundDistribution == other.bottomCenterGroundDistribution &&
            bottomCenterRoadRatio == other.bottomCenterRoadRatio &&
            bottomStats == other.bottomStats &&
            passablePixelCount == other.passablePixelCount &&
            navigationPassableRatio == other.navigationPassableRatio &&
            obstaclePixelCount == other.obstaclePixelCount &&
            classCounts.contentEquals(other.classCounts)
    }

    override fun hashCode(): Int {
        var result = passableMask.hashCode()
        result = 31 * result + obstacleMask.hashCode()
        result = 31 * result + roadRatio.hashCode()
        result = 31 * result + hasTrafficLight.hashCode()
        result = 31 * result + bottomCenterGroundDistribution.hashCode()
        result = 31 * result + bottomCenterRoadRatio.hashCode()
        result = 31 * result + bottomStats.hashCode()
        result = 31 * result + passablePixelCount
        result = 31 * result + navigationPassableRatio.hashCode()
        result = 31 * result + obstaclePixelCount
        result = 31 * result + classCounts.contentHashCode()
        return result
    }
}
