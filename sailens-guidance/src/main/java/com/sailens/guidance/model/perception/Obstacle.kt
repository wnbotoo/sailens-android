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
    /** The frame whose detection last updated this track; null where the tracker was not told. */
    val measurement: ObstacleMeasurement? = null,
) {
    fun isStable(minFrames: Int = 3): Boolean = stableFrames >= minFrames

    fun shouldAnnounce(
        minUrgency: UrgencyLevel = UrgencyLevel.MEDIUM,
        minFrames: Int = 3,
    ): Boolean {
        return urgency >= minUrgency && isStable(minFrames)
    }
}

/**
 * The frame whose detection last updated a track (det-first plan D1.5).
 *
 * A track the latest detection run did not match keeps reporting its last box, distance and
 * estimate, stamped with the current time; so does every predicted frame. Only this says whether
 * what a [DetectedObstacle] reports was measured on the frame at hand: compare [frameSequence] with
 * that frame's, never times -- two updates can fall in the same millisecond. Observation only: no
 * decision reads it.
 */
data class ObstacleMeasurement(
    /** `ImageFrame.sequenceNumber` of the frame the detection ran on. */
    val frameSequence: Long,
    /** `ImageFrame.timestamp` of that frame: when the camera took it, on the camera's clock (ns). */
    val frameTimestampNanos: Long,
    /** The tracker's time of the update (its `lastSeen`): when processing finished, not when the frame was taken. */
    val processedAt: Long,
)

/** The frame a batch of detections came from, as the tracker is told it. */
data class ObstacleSourceFrame(val sequenceNumber: Long, val timestampNanos: Long)
