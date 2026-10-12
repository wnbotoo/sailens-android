package com.sailens.guidance.motion

import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MotionTrackerTest {

    /** Quaternion (x, y, z, w) of a rotation by [angle] radians about a unit [axis]. */
    private fun quat(axis: Triple<Double, Double, Double>, angle: Double): DoubleArray {
        val s = sin(angle / 2)
        return doubleArrayOf(axis.first * s, axis.second * s, axis.third * s, cos(angle / 2))
    }

    private fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    /**
     * The rotation vector of a phone held upright in portrait, camera level and pointing at
     * heading [headingRad] clockwise from world north (turned right by that much), pitched down by
     * [pitchRad].
     */
    private fun upright(headingRad: Double, pitchRad: Double = 0.0): FloatArray {
        // Upright, camera north: device axes are x east, y up, z south -- a +90 degree turn about east.
        val stand = quat(Triple(1.0, 0.0, 0.0), Math.PI / 2 - pitchRad)
        // Turning right is clockwise seen from above: negative about world up.
        val turn = quat(Triple(0.0, 0.0, 1.0), -headingRad)
        return mul(turn, stand).map(Double::toFloat).toFloatArray()
    }

    @Test
    fun `heading follows the camera and grows turning right`() {
        val tracker = MotionTracker()
        for (degrees in listOf(0.0, 30.0, 90.0, -45.0, 170.0)) {
            tracker.onGameRotationVector(1_000, upright(Math.toRadians(degrees), pitchRad = Math.toRadians(25.0)))
            assertEquals("heading $degrees", Math.toRadians(degrees).toFloat(), tracker.state(1_000, false).headingRad, 1e-4f)
        }
    }

    @Test
    fun `yaw rate is positive turning right and wraps across the half turn`() {
        val tracker = MotionTracker()
        tracker.onGameRotationVector(0, upright(Math.toRadians(170.0)))
        tracker.onGameRotationVector(100_000_000, upright(Math.toRadians(-170.0)))
        // 20 degrees to the right in 100 ms, not 340 to the left.
        assertEquals(Math.toRadians(200.0).toFloat(), tracker.state(100_000_000, false).yawRateRadPerS, 1e-3f)
    }

    @Test
    fun `pointing straight down carries the heading over without a rate`() {
        val tracker = MotionTracker()
        tracker.onGameRotationVector(0, upright(Math.toRadians(40.0)))
        tracker.onGameRotationVector(50_000_000, upright(Math.toRadians(40.0), pitchRad = Math.toRadians(89.0)))
        val down = tracker.state(50_000_000, false)
        assertEquals(OrientationQuality.DEGRADED, down.orientationQuality)
        assertEquals(Math.toRadians(40.0).toFloat(), down.headingRad, 1e-4f)
        assertEquals(0f, down.yawRateRadPerS, 0f)
        // The turn made while pointing down was not observed: no rate on the way back either.
        tracker.onGameRotationVector(100_000_000, upright(Math.toRadians(120.0)))
        val back = tracker.state(100_000_000, false)
        assertEquals(OrientationQuality.OK, back.orientationQuality)
        assertEquals(0f, back.yawRateRadPerS, 0f)
    }

    @Test
    fun `no recent sample is unavailable`() {
        val tracker = MotionTracker(maxSampleAgeNanos = 200_000_000)
        assertEquals(OrientationQuality.UNAVAILABLE, tracker.state(0, false).orientationQuality)
        tracker.onGameRotationVector(0, upright(0.0))
        assertEquals(OrientationQuality.OK, tracker.state(150_000_000, true).orientationQuality)
        assertEquals(OrientationQuality.UNAVAILABLE, tracker.state(250_000_000, true).orientationQuality)
        tracker.reset()
        assertEquals(OrientationQuality.UNAVAILABLE, tracker.state(0, false).orientationQuality)
    }

    @Test
    fun `three-value rotation vectors reconstruct w`() {
        val four = upright(Math.toRadians(60.0), Math.toRadians(20.0))
        // Android may omit w; the sign convention keeps it non-negative.
        val q = if (four[3] < 0) four.map { -it }.toFloatArray() else four
        val fromThree = MotionTracker.cameraForwardInWorld(q.copyOf(3))!!
        val fromFour = MotionTracker.cameraForwardInWorld(q)!!
        assertEquals(fromFour.x, fromThree.x, 1e-5f)
        assertEquals(fromFour.y, fromThree.y, 1e-5f)
        assertEquals(fromFour.z, fromThree.z, 1e-5f)
    }

    @Test
    fun `the sample buffer finds the nearest sample and keeps only the newest`() {
        val buffer = SensorSampleBuffer(capacity = 4)
        assertNull(buffer.nearest(0))
        for (i in 0 until 6) buffer.add(SensorSample(i * 10L, Vec3(i.toFloat(), 0f, 0f)))
        assertEquals(20L, buffer.nearest(0)!!.timestampNanos) // 0 and 10 were overwritten
        assertEquals(30L, buffer.nearest(33)!!.timestampNanos)
        assertEquals(50L, buffer.latest()!!.timestampNanos)
        buffer.clear()
        assertNull(buffer.latest())
    }
}
