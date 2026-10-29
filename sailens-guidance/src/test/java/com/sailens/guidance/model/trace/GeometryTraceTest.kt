package com.sailens.guidance.model.trace

import com.sailens.guidance.geometry.CameraGeometryResolver
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.geometry.toCameraFacts
import com.sailens.guidance.geometry.withGeometry
import com.sailens.guidance.motion.MotionTracker
import com.sailens.guidance.sim.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The camera geometry's per-frame trace fields: filled from the geometry, and readable from a trace line -- including
 * lines written before they existed. (org.json is a stub on the JVM, so the encoder's half is
 * exercised on device; the field names here are the ones it writes.)
 */
class GeometryTraceTest {

    private val base = FrameTrace(
        sessionId = "s", sequenceNumber = 1, frameTimestamp = 0, frameWidth = 1280, frameHeight = 720,
        droppedFramesSinceLast = 0, processFrameMs = 0, inferenceMs = 0, analyzeSceneMs = 0,
        decideEventsMs = 0, totalPipelineMs = 0, obstacleCount = 0, eventCount = 0, isBlocked = false,
        isNarrowing = false, isRoadDangerous = false, navigationPassableRatio = 0.0, blockageConfidence = 0.0,
        verticalReachRatio = 0.0, floodReachRatio = 0.0, widthRetentionP25 = 0.0, messageKeys = emptyList(),
    )

    @Test
    fun `geometry fills the trace fields`() {
        val facts = Scenarios.straightClearPath.facts(0)
        val t = Math.toRadians(25.0)
        val gravity = SensorSample(facts.frame.sensorTimestampNanos - 2_000_000, Vec3(0f, (9.81 * cos(t)).toFloat(), (9.81 * sin(t)).toFloat()))
        val geometry = CameraGeometryResolver().resolve(facts.camera.toCameraFacts(), FrameFacts.of(facts.frame), { gravity }, PhoneGeometrySettings.DEFAULT)
        val motion = MotionTracker().state(facts.frame.sensorTimestampNanos, isStationary = false)

        val trace = base.withGeometry(geometry, motion)

        assertEquals("ok", trace.geometryQuality)
        assertEquals(emptyList<String>(), trace.geometryIssues)
        assertEquals("camera_realtime", trace.geometryTimeBasis)
        assertEquals(2.0, trace.gravityAgeMs!!, 1e-9)
        assertEquals(25.0, trace.pitchDownDeg!!, 1e-3)
        assertEquals(0.0, trace.rollDeg!!, 1e-6)
        assertEquals("calibration", trace.intrinsicsSource)
        assertEquals(899.25, trace.focalPx!!, 1e-2)
        // No rotation vector: orientation unavailable, so no heading is claimed.
        assertEquals("unavailable", trace.orientationQuality)
        assertNull(trace.headingDeg)
    }

    @Test
    fun `a frame line with geometry parses, and one without parses as none`() {
        val common = """"type":"frame","sessionId":"s","sequenceNumber":1,"frameTimestamp":0,"frameWidth":1280,""" +
            """"frameHeight":720,"droppedFramesSinceLast":0,"processFrameMs":0,"inferenceMs":0,"analyzeSceneMs":0,""" +
            """"decideEventsMs":0,"totalPipelineMs":0,"obstacleCount":0,"eventCount":0,"isBlocked":false,""" +
            """"isNarrowing":false,"isRoadDangerous":false,"messageKeys":[]"""
        val withGeometry = """{$common,"geometryQuality":"degraded","geometryIssues":["stale_gravity"],""" +
            """"geometryTimeBasis":"camera_realtime","gravityAgeMs":140.5,"pitchDownDeg":22.5,"rollDeg":-3.0,""" +
            """"intrinsicsSource":"calibration","focalPx":899.25,"headingDeg":12.0,"yawRateDegPerS":4.5,""" +
            """"orientationQuality":"ok"}"""
        val (recent, old) = TraceReplayParser.parse(listOf(withGeometry, "{$common}")).frames

        assertEquals("degraded", recent.geometryQuality)
        assertEquals(listOf("stale_gravity"), recent.geometryIssues)
        assertEquals(140.5, recent.gravityAgeMs!!, 0.0)
        assertEquals(22.5, recent.pitchDownDeg!!, 0.0)
        assertEquals(-3.0, recent.rollDeg!!, 0.0)
        assertEquals(899.25, recent.focalPx!!, 0.0)
        assertEquals(12.0, recent.headingDeg!!, 0.0)
        assertEquals("ok", recent.orientationQuality)

        assertEquals("none", old.geometryQuality)
        assertEquals("none", old.geometryTimeBasis)
        assertEquals("none", old.intrinsicsSource)
        assertEquals("none", old.orientationQuality)
        assertNull(old.pitchDownDeg)
        assertNull(old.headingDeg)
    }
}
