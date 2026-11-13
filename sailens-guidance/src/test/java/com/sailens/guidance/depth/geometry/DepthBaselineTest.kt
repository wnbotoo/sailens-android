package com.sailens.guidance.depth.geometry

import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.sim.Pose
import com.sailens.guidance.sim.Scenarios
import com.sailens.guidance.sim.SyntheticCamera
import com.sailens.guidance.sim.renderDisparity
import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.ResizePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The baseline catches a difference of one bit in any field it freezes, and names the field. */
class DepthBaselineTest {

    private val camera = SyntheticCamera()
    private val transform = DepthInputTransform.create(720, 1280, 126, 224, ResizePolicy.PAD_KEEP_ASPECT)
    private val scale = 720f / camera.mapWidth
    private val intrinsics = CameraIntrinsics(camera.focal * scale, camera.focal * scale, camera.cx * scale, camera.cy * scale, 720, 1280, IntrinsicsSource.CALIBRATION)

    private fun fit(pose: Pose, fitter: GroundFitter = GroundFitter()): Pair<GroundObservation, GroundFitter> {
        val world = Scenarios.curbAndDrop.frames[40].world
        val values = renderDisparity(camera, pose, world, 126, 224, 720, 1280, { x, y -> transform.toAnalysisX(x) to transform.toAnalysisY(y) })
        val t = Math.toRadians(pose.pitchDownDeg.toDouble())
        val observation = fitter.fit(DisparityMap(126, 224, values, transform), intrinsics, Vec3(0f, cos(t).toFloat(), sin(t).toFloat()), 90, 160, 5L)
        return observation to fitter
    }

    private fun line(observation: GroundObservation, fitter: GroundFitter) =
        DepthBaseline.line(listOf("case" to "drop"), observation, fitter).text

    @Test
    fun `the same fit is the same line, and parses back to its fields`() {
        val (a, fa) = fit(Pose(1.3f, 25f))
        val (b, fb) = fit(Pose(1.3f, 25f))
        assertEquals(line(a, fa), line(b, fb))
        val parsed = DepthBaseline.parse(line(a, fa))
        assertEquals(line(a, fa), parsed.text)
        assertEquals("case=drop", parsed.label)
        assertTrue(DepthBaseline.compare(listOf("# header", line(a, fa)), listOf("# header", line(b, fb))).same)
    }

    @Test
    fun `one bit of a float, one cell of a mask, or the guard's history is a difference, by name`() {
        val (o, fitter) = fit(Pose(1.3f, 25f))
        val reference = line(o, fitter)
        val shifted = o.copy(disparityShift = Float.fromBits(o.disparityShift.toRawBits() xor 1))
        val oneCell = BinaryMask.fromPackedBits(o.groundMask.width, o.groundMask.height, o.groundMask.copyPackedBits())
            .also { it.set(0, 0, !it.get(0, 0)) }
        for ((changed, field) in listOf(
            line(shifted, fitter) to "shift",
            line(o.copy(groundMask = oneCell), fitter) to "ground",
            line(o.copy(frameTimeNanos = 6L), fitter) to "time",
        )) {
            val comparison = DepthBaseline.compare(listOf(reference), listOf(changed))
            assertEquals(changed, 1, comparison.count)
            assertTrue(comparison.toString(), comparison.kept.single().contains(" $field expected "))
        }
        // The same observation from a fitter with another history.
        val other = GroundFitter().also { fit(Pose(1.3f, 25f, walkedM = 3f), it) }
        val history = DepthBaseline.compare(listOf(reference), listOf(line(o, other)))
        assertFalse(history.same)
        assertTrue(history.toString(), history.kept.any { it.contains(" level expected ") })
        assertTrue(DepthBaseline.compare(listOf(reference), listOf(reference, reference)).kept.first().contains("1 line(s) expected, 2 given"))
    }
}
