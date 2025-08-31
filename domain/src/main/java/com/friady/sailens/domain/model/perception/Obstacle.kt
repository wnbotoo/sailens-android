package com.friady.sailens.domain.model.perception

import com.friady.sailens.domain.model.common.DirectionZone
import com.friady.sailens.domain.model.common.DistanceLevel
import com.friady.sailens.domain.model.common.NormalizedRect
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.common.UrgencyLevel
import java.util.UUID

/**
 * 原始障碍物（未跟踪）
 */
data class RawObstacle(
    val boundingBox: NormalizedRect,
    val category: ObstacleCategory,
    val zone: DirectionZone,
    val distance: DistanceLevel,
    val confidence: Float,
    val areaRatio: Float,
)

/**
 * 检测到的障碍物（跟踪后）
 */
data class DetectedObstacle(
    val id: UUID = UUID.randomUUID(),
    val boundingBox: NormalizedRect,
    val category: ObstacleCategory,
    val zone: DirectionZone,
    val distance: DistanceLevel,
    val urgency: UrgencyLevel,
    val confidence: Float,
    val stableFrames: Int,
    val areaRatio: Float,
    val timestamp: Long,
) {
    fun isStable(minFrames: Int = 3): Boolean = stableFrames >= minFrames

    fun shouldAnnounce(
        minUrgency: UrgencyLevel = UrgencyLevel.MEDIUM,
        minFrames: Int = 3,
    ): Boolean {
        return urgency >= minUrgency && isStable(minFrames)
    }
}
