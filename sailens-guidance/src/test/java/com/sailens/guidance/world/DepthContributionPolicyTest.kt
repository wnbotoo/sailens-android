package com.sailens.guidance.world

import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.depth.geometry.DepthCells
import com.sailens.guidance.depth.geometry.DepthReading
import com.sailens.guidance.depth.geometry.DepthReadingTimes
import com.sailens.guidance.depth.geometry.DepthUnit
import com.sailens.guidance.depth.geometry.DepthViewGeometry
import com.sailens.guidance.depth.geometry.ObservationId
import com.sailens.guidance.depth.geometry.scaleBasisOf
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundFitValidity
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.motion.MotionTracker
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.CapabilityStatus
import com.sailens.vision.depth.DepthKind
import com.sailens.vision.depth.MeasurementKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class DepthContributionPolicyTest {
    private val capture = 1_010_000_000L
    private val measured = 1_000_000_000L
    private val intrinsics = CameraIntrinsics(400f, 400f, 320f, 240f, 640, 480, IntrinsicsSource.CALIBRATION)
    private val down = Vec3(0f, 1f, 0f)
    private val phone = PhoneGeometrySettings(1.2f, HeightSource.PRESET)
    private val main = CameraGeometry(capture, TimeBasis.CAMERA_REALTIME, intrinsics, down, 0L, phone, GeometryQuality.Ok)
    private val view = DepthViewGeometry(intrinsics, down, 1, 1, phone, TimeBasis.CAMERA_REALTIME, GeometryQuality.Ok)
    private val qualification = BaselineQualification.Qualified
    private val key = MeasurementKey(2, 4)

    @Test
    fun `fresh relative and metric evidence have identical trust requirements`() {
        val tracker = MotionTracker()
        tracker.onGameRotationVector(measured, pose(yawDegrees = 0.0))
        tracker.onGameRotationVector(capture, pose(yawDegrees = 0.0))
        val from = tracker.state(measured, true)
        val to = tracker.state(capture, true)
        for (kind in DepthKind.entries) {
            val reading = valid(kind)
            val assessed = DepthContributionPolicy.assess(reading, main, qualification, from, to)
            assertEquals(setOf(DepthRejection.SUPPORT_UNPROVEN, DepthRejection.INPUT_UNPROVEN,
                DepthRejection.TRANSLATION_UNKNOWN, DepthRejection.ROTATION_UNCERTAINTY_UNKNOWN), assessed.reasons)
            assertEquals(0f, assessed.headingDeltaDegrees ?: Float.NaN, .0001f)
            assertEquals(key, assessed.key)
            // The stationarity flag cannot supply a translation measurement.
            val noStationaryFlag = DepthContributionPolicy.assess(reading, main, qualification,
                from.copy(isStationary = false), to.copy(isStationary = false))
            assertEquals(assessed, noStationaryFlag)
        }
    }

    @Test
    fun `real rotation samples diagnose misalignment without changing trust`() {
        val tracker = MotionTracker()
        tracker.onGameRotationVector(measured, pose(0.0))
        tracker.onGameRotationVector(capture, pose(10.0))
        val from = tracker.state(measured, false)
        val to = tracker.state(capture, false)
        val reading = valid(DepthKind.METRIC_DEPTH)
        val assessed = DepthContributionPolicy.assess(reading, main, qualification, from, to)
        assertEquals(10f, kotlin.math.abs(assessed.headingDeltaDegrees ?: Float.NaN), .001f)
        assertTrue(assessed.reasons.contains(DepthRejection.ROTATION_UNCERTAINTY_UNKNOWN))
        assertTrue(assessed.reasons.contains(DepthRejection.TRANSLATION_UNKNOWN))
        val wrongTime = DepthContributionPolicy.assess(reading, main, qualification, to, from)
        assertNull(wrongTime.headingDeltaDegrees)
    }

    @Test
    fun `capability status remains the only stale authority across repeat withdraw and recovery`() {
        val reading = valid(DepthKind.RELATIVE_DISPARITY)
        val assessed = DepthContributionPolicy.assess(reading, main, qualification, null, null)
        val reprojected = reading.copy(observationId = ObservationId(3, 8), times = reading.times!!.copy(viewTimeNanos = capture))
        assertEquals(key, DepthContributionPolicy.assess(reprojected, main, qualification, null, null).key)
        val stale = reading.copy(status = CapabilityStatus.Unavailable(setOf("stale")))
        val rejected = DepthContributionPolicy.assess(stale, main, qualification, null, null)
        assertEquals(setOf(DepthRejection.UNAVAILABLE), rejected.reasons)
        assertEquals(setOf("stale"), rejected.capabilityIssues)
        assertEquals(setOf(DepthRejection.NOT_CONFIGURED),
            DepthContributionPolicy.assess(DepthReading.NOT_CONFIGURED, main, qualification, null, null).reasons)
        assertEquals(assessed, DepthContributionPolicy.assess(reading, main, qualification, null, null))
    }

    private fun valid(kind: DepthKind): DepthReading {
        val observation = GroundObservation(measured, GroundFitValidity.VALID,
            BinaryMask(1, 1).apply { set(0, 0, true) }, BinaryMask(1, 1), BinaryMask(1, 1),
            1f, 0f, 1f, true, 1f)
        val unit = if (kind == DepthKind.METRIC_DEPTH) DepthUnit.METERS else DepthUnit.PHONE_HEIGHTS
        return DepthReading(CapabilityStatus.Available, kind, scaleBasisOf(kind), ObservationId(1, 4),
            DepthReadingTimes(measured, measured, key, capture, capture - measured, 200_000_000L),
            view, observation, DepthCells(unit, 1, 1, floatArrayOf(2f), floatArrayOf(0f), floatArrayOf(1f)))
    }

    // Rotate the rear optical axis up to horizontal, then yaw it about the world's vertical axis.
    private fun pose(yawDegrees: Double): FloatArray {
        val yaw = Math.toRadians(yawDegrees) / 2
        val tiltSin = sin(Math.PI / 4)
        val tiltCos = cos(Math.PI / 4)
        return floatArrayOf(
            (cos(yaw) * tiltSin).toFloat(), (sin(yaw) * tiltSin).toFloat(),
            (sin(yaw) * tiltCos).toFloat(), (cos(yaw) * tiltCos).toFloat(),
        )
    }
}
