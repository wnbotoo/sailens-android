package com.sailens.guidance.geometry

import com.sailens.guidance.sim.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CameraGeometryTest {

    private val g = 9.81f

    // ---- gravity in the camera frame ---------------------------------------------------------

    @Test
    fun `portrait pitch comes out as forward-down gravity`() {
        for (pitch in listOf(-20.0, 0.0, 25.0, 60.0)) {
            val t = Math.toRadians(pitch)
            // Phone upright in portrait, camera pitched down by t; the sensor reports the reaction.
            val device = Vec3(0f, (g * cos(t)).toFloat(), (g * sin(t)).toFloat())
            val down = checkNotNull(GravityInCamera.resolve(device, sensorOrientationDegrees = 90, rotationDegrees = 90))
            assertEquals(0f, down.x, 1e-5f)
            assertEquals(cos(t).toFloat(), down.y, 1e-5f)
            assertEquals(sin(t).toFloat(), down.z, 1e-5f)
        }
    }

    /**
     * Whichever way the phone is turned, the pipeline rotates the frame upright, and gravity must
     * then point down the image. Each case is also rolled 10 degrees clockwise (as the user sees the
     * screen), so the edge on the user's right dips and gravity leans to image right -- which
     * exercises both image axes. Rotations as CameraX gives them for a back camera mounted at 90.
     */
    @Test
    fun `gravity points down the upright image however the phone is turned`() {
        val a = Math.toRadians(10.0)
        val c = (g * cos(a)).toFloat()
        val s = (g * sin(a)).toFloat()
        // The reaction points up: away from the lowered edges.
        val cases = listOf(
            // Right is device +x, down is device -y.
            "portrait" to (Vec3(-s, c, 0f) to 90),
            // Top to the left: right is device -y, down is device -x.
            "turned left" to (Vec3(c, s, 0f) to 0),
            // Right is device -x, down is device +y.
            "upside down" to (Vec3(s, -c, 0f) to 270),
            // Top to the right: right is device +y, down is device +x.
            "turned right" to (Vec3(-c, -s, 0f) to 180),
        )
        for ((name, case) in cases) {
            val (device, rotation) = case
            val down = checkNotNull(GravityInCamera.resolve(device, 90, rotation))
            assertEquals(name, sin(a).toFloat(), down.x, 1e-5f)
            assertEquals(name, cos(a).toFloat(), down.y, 1e-5f)
            assertEquals(name, 0f, down.z, 1e-5f)
        }
    }

    @Test
    fun `no gravity and odd rotations resolve to nothing`() {
        assertNull(GravityInCamera.resolve(Vec3(0f, 0f, 0f), 90, 90))
        assertNull(GravityInCamera.resolve(Vec3(0f, g, 0f), 90, 45))
    }

    // ---- per-frame geometry and its quality --------------------------------------------------

    private val camera = Scenarios.straightClearPath.facts(0).camera.toCameraFacts()
    private val frameFacts = FrameFacts.of(Scenarios.straightClearPath.facts(0).frame)
    private val resolver = CameraGeometryResolver()
    private val level = Vec3(0f, g, 0f)

    private fun resolve(
        camera: CameraFacts? = this.camera,
        frame: FrameFacts = frameFacts,
        sample: SensorSample? = SensorSample(frameFacts.sensorTimestampNanos - 5_000_000, level),
    ) = resolver.resolve(camera, frame, { sample }, PhoneGeometrySettings.DEFAULT)

    @Test
    fun `calibrated camera, realtime clock and fresh gravity is OK`() {
        val geometry = resolve()
        assertEquals(GeometryQuality.Ok, geometry.quality)
        assertEquals(TimeBasis.CAMERA_REALTIME, geometry.timeBasis)
        assertEquals(5_000_000L, geometry.gravityAgeNanos)
        assertEquals(0f, geometry.pitchDownDegrees!!, 1e-4f)
    }

    @Test
    fun `an unknown camera clock falls back to receipt time and is degraded`() {
        val unknown = camera.copy(clock = CameraClock.UNKNOWN)
        val received = frameFacts.copy(receivedElapsedRealtimeNanos = 7_000_000_000)
        var askedFor = 0L
        val geometry = resolver.resolve(unknown, received, { askedFor = it; SensorSample(it, level) }, PhoneGeometrySettings.DEFAULT)
        assertEquals(7_000_000_000, askedFor)
        assertEquals(TimeBasis.SOURCE_RECEIPT, geometry.timeBasis)
        assertEquals(GeometryQuality.Degraded(setOf(GeometryIssue.UNMEASURED_ALIGNMENT)), geometry.quality)
    }

    @Test
    fun `no time to align with is unavailable`() {
        // The camera's base is not known and the source gave no receipt time.
        val geometry = resolve(camera = camera.copy(clock = CameraClock.NOT_REPORTED), frame = frameFacts.copy(receivedElapsedRealtimeNanos = 0))
        val quality = geometry.quality as GeometryQuality.Unavailable
        assertTrue(GeometryIssue.NO_FRAME_TIME in quality.issues)
        assertNull(geometry.gravityInCamera)
    }

    @Test
    fun `stale gravity is degraded, missing gravity is unavailable`() {
        val stale = resolve(sample = SensorSample(frameFacts.sensorTimestampNanos - 300_000_000, level))
        assertEquals(GeometryQuality.Degraded(setOf(GeometryIssue.STALE_GRAVITY)), stale.quality)
        val none = resolve(sample = null)
        assertEquals(GeometryQuality.Unavailable(setOf(GeometryIssue.NO_GRAVITY)), none.quality)
    }

    @Test
    fun `estimated intrinsics are degraded, none at all is unavailable`() {
        val estimated = resolve(camera = camera.copy(lensIntrinsicCalibration = null, physicalSizeWidthMm = 7.4f, physicalSizeHeightMm = 5.55f))
        assertEquals(GeometryQuality.Degraded(setOf(GeometryIssue.ESTIMATED_INTRINSICS)), estimated.quality)
        val missing = resolve(camera = null)
        val quality = missing.quality as GeometryQuality.Unavailable
        assertTrue(GeometryIssue.NO_INTRINSICS in quality.issues)
        assertTrue(GeometryIssue.NO_SENSOR_ORIENTATION in quality.issues)
    }

    /** Every simulated frame resolves back to the scenario's own truth: pitch and intrinsics. */
    @Test
    fun `scenario raw facts resolve to the scenario's pose`() {
        for (scenario in Scenarios.all) {
            scenario.frames.forEachIndexed { index, frame ->
                val facts = scenario.facts(index)
                val gravity = facts.gravity.map { SensorSample(it.timestampNanos, Vec3(it.values[0], it.values[1], it.values[2])) }
                val geometry = resolver.resolve(
                    facts.camera.toCameraFacts(),
                    FrameFacts.of(facts.frame),
                    { time -> gravity.minByOrNull { kotlin.math.abs(it.timestampNanos - time) } },
                    PhoneGeometrySettings.DEFAULT,
                )
                val where = "${scenario.name} frame ${index + 1}"
                assertEquals(where, GeometryQuality.Ok, geometry.quality)
                assertEquals(where, frame.pose.pitchDownDeg, geometry.pitchDownDegrees!!, 1e-3f)
                assertEquals(where, scenario.camera.focal, geometry.intrinsics!!.scaledTo(360, 640).fx, 1e-3f)
            }
        }
    }
}
