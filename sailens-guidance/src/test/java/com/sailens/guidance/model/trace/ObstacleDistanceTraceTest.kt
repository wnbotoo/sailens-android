package com.sailens.guidance.model.trace

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.depth.ContactValidity
import com.sailens.guidance.depth.DistanceEstimate
import com.sailens.guidance.depth.GroundContactEstimate
import com.sailens.guidance.geometry.withObstacleDistances
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.UrgencyLevel
import com.sailens.guidance.model.perception.DetectedObstacle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3a's per-obstacle trace fields: filled from the tracked obstacles, and readable from a trace line. */
class ObstacleDistanceTraceTest {

    private val base = FrameTrace(
        sessionId = "s", sequenceNumber = 1, frameTimestamp = 0, frameWidth = 1280, frameHeight = 720,
        droppedFramesSinceLast = 0, processFrameMs = 0, inferenceMs = 0, analyzeSceneMs = 0,
        decideEventsMs = 0, totalPipelineMs = 0, obstacleCount = 0, eventCount = 0, isBlocked = false,
        isNarrowing = false, isRoadDangerous = false, navigationPassableRatio = 0.0, blockageConfidence = 0.0,
        verticalReachRatio = 0.0, floodReachRatio = 0.0, widthRetentionP25 = 0.0, messageKeys = emptyList(),
    )

    private fun obstacle(distance: DistanceLevel, estimate: DistanceEstimate?) = DetectedObstacle(
        boundingBox = NormalizedRect(0.4f, 0.4f, 0.2f, 0.3f), category = ObstacleCategory.PERSON,
        zone = DirectionZone.CENTER, distance = distance, urgency = UrgencyLevel.HIGH, confidence = 0.9f,
        stableFrames = 3, areaRatio = 0.06f, timestamp = 0, distanceEstimate = estimate,
    )

    @Test
    fun `tracked obstacles fill the distance fields`() {
        val raised = DistanceEstimate(
            used = DistanceLevel.NEAR, today = DistanceLevel.MEDIUM,
            contact = GroundContactEstimate(ContactValidity.VALID, 1.1f, 1.3f, 1.6f), contactLevel = DistanceLevel.NEAR,
        )
        val trace = base.withObstacleDistances(listOf(obstacle(DistanceLevel.NEAR, raised), obstacle(DistanceLevel.FAR, null)), groundContactEnabled = true)

        assertTrue(trace.groundContactEnabled)
        val (first, second) = trace.obstacleDistances
        assertEquals(ObstacleDistanceTrace("person", "center", "valid", 1.1f.toDouble(), 1.3f.toDouble(), 1.6f.toDouble(), "medium", "near", "near"), first)
        // A track with no estimate (made before M3a, or by a path that computes none) reads as today's.
        assertEquals(ObstacleDistanceTrace("person", "center", "none", levelToday = "far", levelUsed = "far"), second)
    }

    @Test
    fun `a frame line with distances parses, and one without parses as none`() {
        val common = """"type":"frame","sessionId":"s","sequenceNumber":1,"frameTimestamp":0,"frameWidth":1280,""" +
            """"frameHeight":720,"droppedFramesSinceLast":0,"processFrameMs":0,"inferenceMs":0,"analyzeSceneMs":0,""" +
            """"decideEventsMs":0,"totalPipelineMs":0,"obstacleCount":1,"eventCount":0,"isBlocked":false,""" +
            """"isNarrowing":false,"isRoadDangerous":false,"messageKeys":[]"""
        val withDistances = """{$common,"phoneHeightM":1.2,"heightSource":"preset","groundContactEnabled":true,""" +
            """"obstacleDistances":[{"category":"person","zone":"center","contactValidity":"occluded","lowerBoundM":2.5,""" +
            """"pointM":3.0,"levelToday":"medium","levelNew":"medium","levelUsed":"medium"}]}"""
        val (recent, old) = TraceReplayParser.parse(listOf(withDistances, "{$common}")).frames

        assertEquals(1.2, recent.phoneHeightM!!, 0.0)
        assertEquals("preset", recent.heightSource)
        assertTrue(recent.groundContactEnabled)
        assertEquals(
            listOf(ObstacleDistanceTrace("person", "center", "occluded", 2.5, 3.0, null, "medium", "medium", "medium")),
            recent.obstacleDistances,
        )

        assertNull(old.phoneHeightM)
        assertEquals("none", old.heightSource)
        assertFalse(old.groundContactEnabled)
        assertEquals(emptyList<ObstacleDistanceTrace>(), old.obstacleDistances)
    }
}
