package com.sailens.guidance.model.perception

import com.sailens.guidance.depth.DistanceEstimate
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.UrgencyLevel
import java.util.UUID

/**
 * 原始障碍物（未跟踪）
 */
data class RawObstacle(
    val boundingBox: NormalizedRect,
    val category: ObstacleCategory,
    val className: String = category.name.lowercase(),
    val zone: DirectionZone,
    val distance: DistanceLevel,
    val confidence: Float,
    val areaRatio: Float,
    /** The evidence behind [distance] (M3a); null where no estimate was made. */
    val distanceEstimate: DistanceEstimate? = null,
)

/**
 * 检测到的障碍物（跟踪后）
 */
data class DetectedObstacle(
    val id: UUID = UUID.randomUUID(),
    val boundingBox: NormalizedRect,
    val category: ObstacleCategory,
    val className: String = category.name.lowercase(),
    val zone: DirectionZone,
    val distance: DistanceLevel,
    val urgency: UrgencyLevel,
    val confidence: Float,
    val stableFrames: Int,
    val areaRatio: Float,
    val timestamp: Long,
    /** The evidence behind [distance] at the detection that last updated this track (M3a). */
    val distanceEstimate: DistanceEstimate? = null,
) {
    fun isStable(minFrames: Int = 3): Boolean = stableFrames >= minFrames

    fun shouldAnnounce(
        minUrgency: UrgencyLevel = UrgencyLevel.MEDIUM,
        minFrames: Int = 3,
    ): Boolean {
        return urgency >= minUrgency && isStable(minFrames)
    }
}
