package com.sailens.guidance.evaluation

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.depth.ContactValidity
import com.sailens.guidance.depth.DistanceEstimate
import com.sailens.guidance.depth.GroundContactEstimate
import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.GroundRecognition
import com.sailens.guidance.model.analysis.RoadSafetyState
import com.sailens.guidance.model.analysis.SceneElements
import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.analysis.WalkPathConnectivity
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.Severity
import com.sailens.guidance.model.common.UrgencyLevel
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.perception.ObstacleMeasurement
import java.util.UUID

/** Scenes and obstacles for the evaluation's unit tests. */
internal object EvaluationFixtures {

    fun id(n: Int): UUID = BirthOrderTrackIds.idOf(n)

    /** A stable, confident obstacle that passes the gates for its zone unless told otherwise. */
    fun obstacle(
        n: Int,
        category: ObstacleCategory = ObstacleCategory.PERSON,
        zone: DirectionZone = DirectionZone.CENTER,
        distance: DistanceLevel = DistanceLevel.MEDIUM,
        urgency: UrgencyLevel = UrgencyLevel.HIGH,
        box: NormalizedRect = NormalizedRect(x = 0.43f, y = 0.45f, width = 0.14f, height = 0.25f),
        confidence: Float = 0.9f,
        measuredOn: Long? = null,
        cameraNanos: Long = 0,
        contactPointM: Float? = null,
    ): DetectedObstacle = DetectedObstacle(
        id = id(n),
        boundingBox = box,
        category = category,
        zone = zone,
        distance = distance,
        urgency = urgency,
        confidence = confidence,
        stableFrames = 5,
        areaRatio = box.area,
        timestamp = 1_000L,
        distanceEstimate = contactPointM?.let {
            DistanceEstimate(distance, distance, GroundContactEstimate(ContactValidity.VALID, it - 0.3f, it, it + 0.3f), distance)
        },
        measurement = measuredOn?.let { ObstacleMeasurement(it, cameraNanos, 0) },
    )

    fun snapshot(
        obstacles: List<DetectedObstacle> = emptyList(),
        blocked: Boolean = false,
        frameQuality: FrameQuality = FrameQuality.OK,
    ): SceneSnapshot = SceneSnapshot(
        timestamp = 1_000L,
        obstacles = obstacles,
        bottomCoverage = 1f,
        connectivity = if (blocked) {
            connectivity().copy(
                isBlocked = true,
                blockageConfidence = 0.9f,
                blockageSeverity = Severity.SEVERE,
                verticalReachRatio = 0.2f,
                floodReachRatio = 0.05f,
                widthRetentionP25 = 0.1f,
            )
        } else {
            connectivity()
        },
        sceneElements = SceneElements(),
        roadSafety = RoadSafetyState(
            isOnRoad = false,
            isDangerous = false,
            roadRatio = 0f,
            hasVehicleOnRoad = false,
            hasTrafficLight = false,
            dangerConfidence = 0f,
        ),
        groundTypeChange = null,
        frameQuality = frameQuality,
        groundRecognition = GroundRecognition.RECOGNIZED,
    )

    private fun connectivity() = WalkPathConnectivity(
        isBlocked = false,
        isNarrowing = false,
        suggestedBias = null,
        blockageConfidence = 0f,
        narrowingConfidence = 0f,
        blockageSeverity = Severity.NONE,
        narrowingSeverity = Severity.NONE,
        verticalReachRatio = 1f,
        validLayers = 3,
        totalLayers = 3,
        widthRetentionAvg = 1f,
        widthRetentionP25 = 1f,
        widthSlope = 0f,
        floodReachRatio = 1f,
        floodWidthRetentionP25 = 1f,
        floodVisitedRatio = 1f,
    )
}
