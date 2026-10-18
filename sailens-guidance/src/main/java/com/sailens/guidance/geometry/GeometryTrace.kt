package com.sailens.guidance.geometry

import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.ObstacleDistanceTrace
import com.sailens.guidance.motion.MotionState
import com.sailens.guidance.motion.OrientationQuality
import kotlin.math.atan2

/** Camera roll, degrees: positive when gravity leans to image right. */
val CameraGeometry.rollDegrees: Float?
    get() = gravityInCamera?.let { Math.toDegrees(atan2(it.x.toDouble(), it.y.toDouble())).toFloat() }

/** [this] frame's trace with the M3o geometry and motion fields filled in. */
fun FrameTrace.withGeometry(geometry: CameraGeometry, motion: MotionState): FrameTrace {
    val oriented = motion.orientationQuality != OrientationQuality.UNAVAILABLE
    return copy(
        geometryQuality = when (geometry.quality) {
            GeometryQuality.Ok -> "ok"
            is GeometryQuality.Degraded -> "degraded"
            is GeometryQuality.Unavailable -> "unavailable"
        },
        geometryIssues = when (val quality = geometry.quality) {
            GeometryQuality.Ok -> emptyList()
            is GeometryQuality.Degraded -> quality.issues.map { it.name.lowercase() }.sorted()
            is GeometryQuality.Unavailable -> quality.issues.map { it.name.lowercase() }.sorted()
        },
        geometryTimeBasis = geometry.timeBasis.name.lowercase(),
        gravityAgeMs = geometry.gravityAgeNanos?.let { it / 1e6 },
        pitchDownDeg = geometry.pitchDownDegrees?.toDouble(),
        rollDeg = geometry.rollDegrees?.toDouble(),
        intrinsicsSource = geometry.intrinsics?.source?.name?.lowercase() ?: "none",
        focalPx = geometry.intrinsics?.fx?.toDouble(),
        headingDeg = if (oriented) Math.toDegrees(motion.headingRad.toDouble()) else null,
        yawRateDegPerS = if (oriented) Math.toDegrees(motion.yawRateRadPerS.toDouble()) else null,
        orientationQuality = motion.orientationQuality.name.lowercase(),
        phoneHeightM = geometry.phone.heightMeters.toDouble(),
        heightSource = geometry.phone.source.name.lowercase(),
    )
}

/** [this] frame's trace with each tracked obstacle's distance evidence (M3a). */
fun FrameTrace.withObstacleDistances(obstacles: List<DetectedObstacle>, groundContactEnabled: Boolean): FrameTrace = copy(
    groundContactEnabled = groundContactEnabled,
    obstacleDistances = obstacles.map { obstacle ->
        val estimate = obstacle.distanceEstimate
        ObstacleDistanceTrace(
            category = obstacle.category.name.lowercase(),
            zone = obstacle.zone.name.lowercase(),
            contactValidity = estimate?.contact?.validity?.name?.lowercase() ?: "none",
            lowerBoundM = estimate?.contact?.lowerM?.toDouble(),
            pointM = estimate?.contact?.pointM?.toDouble(),
            upperBoundM = estimate?.contact?.upperM?.toDouble(),
            levelToday = (estimate?.today ?: obstacle.distance).name.lowercase(),
            levelNew = estimate?.contactLevel?.name?.lowercase(),
            levelUsed = obstacle.distance.name.lowercase(),
        )
    },
)
