package com.sailens.guidance.geometry

import com.sailens.guidance.sim.GroundPatch
import com.sailens.guidance.sim.Pose
import com.sailens.guidance.sim.SceneClasses
import com.sailens.guidance.sim.SyntheticCamera
import com.sailens.guidance.sim.World
import com.sailens.guidance.sim.renderMetricDepth
import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DepthMap
import com.sailens.vision.depth.DepthTimes
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.MetricDepthMap
import com.sailens.vision.depth.ResizePolicy
import com.sailens.vision.depth.MeasurementKey
import com.sailens.guidance.depth.geometry.DepthCells
import com.sailens.guidance.depth.geometry.DepthReading
import com.sailens.guidance.depth.geometry.DepthReadingTimes
import com.sailens.guidance.depth.geometry.DepthViewGeometry
import com.sailens.guidance.depth.geometry.ObservationId
import com.sailens.guidance.depth.geometry.scaleBasisOf
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.CapabilityStatus
import com.sailens.guidance.world.DepthContributionPolicy
import com.sailens.guidance.world.DepthRejection
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundFitterRefitSafetyTest {
    private val camera = SyntheticCamera()
    private val aw = 720
    private val ah = 1280
    private val mw = 126
    private val mh = 224
    private val transform = DepthInputTransform.create(aw, ah, mw, mh, ResizePolicy.PAD_KEEP_ASPECT)
    private val intrinsics = CameraIntrinsics(899.25f, 899.25f, 360f, 640f, aw, ah, IntrinsicsSource.CALIBRATION)
    private val flat = World(listOf(GroundPatch(SceneClasses.SIDEWALK, -30.0, 30.0, 0.0, 39.9)))

    private fun drop(depth: Double) = World(listOf(
        GroundPatch(SceneClasses.SIDEWALK, -30.0, 30.0, 0.0, 5.0),
        GroundPatch(SceneClasses.TERRAIN, -30.0, 30.0, 5.0, 39.9, -depth),
    ))

    private fun gravity(pose: Pose): Vec3 = Math.toRadians(pose.pitchDownDeg.toDouble()).let {
        Vec3(0f, cos(it).toFloat(), sin(it).toFloat())
    }

    /** A smooth inverse-depth bias concentrated near the bottom, on the simulator's measured surfaces. */
    private fun map(world: World, pose: Pose, metric: Boolean, amplitude: Double, power: Int): DepthMap {
        val depths = renderMetricDepth(camera, pose, world, mw, mh, aw, ah,
            { x, y -> transform.toAnalysisX(x) to transform.toAnalysisY(y) })
        val values = FloatArray(depths.size) { i ->
            val v = transform.toAnalysisY(i / mw + 0.5f)
            val s = ((v / ah - 0.4f) / 0.6f).coerceIn(0f, 1f).toDouble()
            val inverse = (1.0 / depths[i]) * (1.0 + amplitude * s.pow(power))
            if (metric) (1.0 / inverse).toFloat() else (10.0 * inverse + 0.2).toFloat()
        }
        return if (metric) MetricDepthMap(mw, mh, values, null, 0.5f, transform, DepthTimes.ofFrame(0))
        else DisparityMap(mw, mh, values, transform)
    }

    private fun fit(fitter: GroundFitter, map: DepthMap, pose: Pose) =
        fitter.fit(map, intrinsics, gravity(pose), 90, 160)

    private fun coverageHistoryBits(fitter: GroundFitter): List<Int> {
        val field = GroundFitter::class.java.getDeclaredField("recentCoverage")
        field.isAccessible = true
        return (field.get(fitter) as Iterable<*>).map { (it as Float).toRawBits() }
    }

    @Test
    fun `recovering a dominant refit does not advance a held drop's release`() {
        for (depth in listOf(0.4, 0.8)) {
            val fitter = GroundFitter(guardRule = SupportGuardRule.LABEL_THRESHOLD)
            for (remaining in listOf(5.0, 4.0, 3.2, 2.5, 2.0, 1.6, 1.2, 0.9)) {
                val pose = Pose(1.3f, 12f, (5.0 - remaining).toFloat())
                val disparity = map(drop(depth), pose, false, -0.08, 8)
                val observation = fit(fitter, disparity, pose)
                // The lower floor fills the near view; faint false below-ground labels on it must not make a
                // refit alternative release the hold at this point in the approach.
                if (remaining == 0.9) {
                    assertEquals("depth=$depth", GroundFitValidity.SUPPORT_SWITCH, observation.validity)
                    assertTrue(fitter.guardState().holdingSwitch)
                    assertEquals(0, observation.groundMask.countTrue())
                    assertEquals(0, observation.aboveGroundMask.countTrue())
                    assertEquals(0, observation.belowGroundMask.countTrue())
                    assertConsumerRejects(disparity, observation, pose)
                }
            }
        }
    }

    @Test
    fun `a metric refit refusal leaves both guard and confidence history alone`() {
        val pose = Pose(1.3f, 12f)
        val distorted = map(flat, pose, true, -0.10, 4)
        val refused = fit(GroundFitter(), distorted, pose)
        assertEquals(GroundFitValidity.NO_SUPPORT, refused.validity)
        assertEquals(0, refused.groundMask.countTrue())
        assertEquals(0, refused.aboveGroundMask.countTrue())
        assertEquals(0, refused.belowGroundMask.countTrue())
        assertConsumerRejects(distorted, refused, pose)

        val fitter = GroundFitter()
        val beforePose = Pose(1.3f, 25f)
        val steppedGround = drop(1.5)
        val beforeMap = map(steppedGround, beforePose, true, 0.0, 8)
        assertTrue(fit(fitter, beforeMap, beforePose).isValid)
        assertTrue(fitter.guardState().armed)
        val coverageBefore = coverageHistoryBits(fitter)
        assertTrue(coverageBefore.isNotEmpty())
        val copy = fitter.copy()
        assertEquals(coverageBefore, coverageHistoryBits(copy))
        assertEquals(refused, fit(fitter, distorted, pose))
        assertTrue(fitter.sameGuardHistory(copy))
        assertEquals("coverage history after refusal", coverageBefore, coverageHistoryBits(fitter))
        assertEquals(coverageBefore, coverageHistoryBits(copy))

        val walkedPose = Pose(1.3f, 25f, 0.5f)
        val walkedMap = map(steppedGround, walkedPose, true, 0.0, 8)
        val expected = fit(copy, walkedMap, walkedPose)
        val actual = fit(fitter, walkedMap, walkedPose)
        assertTrue(expected.isValid)
        assertTrue(actual.isValid)
        assertEquals(
            "confidence after a different frame",
            expected.confidence.toRawBits(),
            actual.confidence.toRawBits(),
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `a refit refusal on a copy cannot reset the original hold`() {
        val fitter = GroundFitter(guardRule = SupportGuardRule.LABEL_THRESHOLD)
        for (remaining in listOf(5.0, 4.0, 3.2, 2.5, 2.0, 1.6, 1.2, 0.9)) {
            val pose = Pose(1.3f, 12f, (5.0 - remaining).toFloat())
            fit(fitter, map(drop(0.8), pose, false, -0.08, 8), pose)
        }
        assertTrue(fitter.guardState().holdingSwitch)
        val copy = fitter.copy()
        val pose = Pose(1.3f, 12f)
        assertFalse(fit(copy, map(flat, pose, true, -0.10, 4), pose).isValid)
        assertTrue(fitter.sameGuardHistory(copy))
        copy.reset()
        assertFalse(copy.guardState().holdingSwitch)
        assertTrue(fitter.guardState().holdingSwitch)
        assertTrue(fit(copy, map(flat, pose, true, 0.0, 8), pose).isValid)
        assertTrue(fitter.guardState().holdingSwitch)
    }

    private fun assertConsumerRejects(map: DepthMap, observation: GroundObservation, pose: Pose) {
        val phone = PhoneGeometrySettings(pose.heightM, HeightSource.PRESET)
        val view = DepthViewGeometry(intrinsics, gravity(pose), 90, 160, phone, TimeBasis.CAMERA_REALTIME, GeometryQuality.Ok)
        val cells = DepthCells.of(map, observation, view)
        if (map is MetricDepthMap) {
            checkNotNull(cells)
            assertFalse(cells.hasHeights)
        }
        val frame = CameraGeometry(10L, TimeBasis.CAMERA_REALTIME, intrinsics, gravity(pose), 0L, phone, GeometryQuality.Ok)
        val key = MeasurementKey(1, 2)
        val reading = DepthReading(CapabilityStatus.Available, map.kind, scaleBasisOf(map.kind), ObservationId(1, 3),
            DepthReadingTimes(9, 9, key, 10, 1, 200), view, observation, cells)
        val diagnostic = DepthContributionPolicy.assess(reading, frame, BaselineQualification.Qualified, null, null)
        assertEquals(setOf(DepthRejection.FIT_INVALID), diagnostic.reasons)
        assertEquals(observation.validity, diagnostic.fit)
        assertEquals(key, diagnostic.key)
    }
}
