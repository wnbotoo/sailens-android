package com.sailens.guidance.geometry

import com.sailens.guidance.sim.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraIntrinsicsResolverTest {

    private val active = SensorRect(0, 0, 4000, 3000)

    private fun camera(
        calibration: List<Float>? = listOf(3000f, 3100f, 2010f, 1490f, 0f),
        preCorrection: SensorRect? = null,
    ) = CameraFacts(
        sensorOrientationDegrees = 90,
        clock = CameraClock.REALTIME,
        lensIntrinsicCalibration = calibration,
        activeArray = active,
        preCorrectionActiveArray = preCorrection,
        pixelArrayWidth = 4032,
        pixelArrayHeight = 3024,
        physicalSizeWidthMm = 6.4f,
        physicalSizeHeightMm = 4.8f,
        availableFocalLengthsMm = listOf(4.8f),
    )

    // 1280x720 buffer from the 4000x3000 array: scale 0.32, 120 px cropped off the top.
    private val transform = listOf(0.32f, 0f, 0f, 0f, 0.32f, -120f, 0f, 0f, 1f)

    private fun frame(rotation: Int, transform: List<Float>? = this.transform) =
        FrameFacts(1280, 720, rotation, 0L, 0L, transform)

    @Test
    fun `every simulator scenario resolves to the simulator's class-map intrinsics`() {
        for (scenario in Scenarios.all) {
            val facts = scenario.facts(0)
            val intrinsics = checkNotNull(CameraIntrinsicsResolver.resolve(facts.camera.toCameraFacts(), FrameFacts.of(facts.frame)))
                .scaledTo(scenario.camera.mapWidth, scenario.camera.mapHeight)
            assertEquals(scenario.camera.focal, intrinsics.fx, 1e-3f)
            assertEquals(scenario.camera.focal, intrinsics.fy, 1e-3f)
            assertEquals(scenario.camera.cx, intrinsics.cx, 1e-3f)
            assertEquals(scenario.camera.cy, intrinsics.cy, 1e-3f)
            assertEquals(IntrinsicsSource.CALIBRATION, intrinsics.source)
            assertFalse(intrinsics.bufferMappingAssumed)
        }
    }

    /**
     * For each rotation, a ray projected the long way -- sensor pixel, buffer pixel, buffer rotated
     * upright -- must land where the resolved upright intrinsics put it. fx != fy and an off-centre
     * principal point catch a swapped or mis-flipped axis.
     */
    @Test
    fun `resolved intrinsics agree with rotating the buffer for every rotation`() {
        val k = camera().lensIntrinsicCalibration!!
        for (rotation in listOf(0, 90, 180, 270)) {
            val intrinsics = checkNotNull(CameraIntrinsicsResolver.resolve(camera(), frame(rotation)))
            val rotated = rotation == 90 || rotation == 270
            assertEquals(if (rotated) 720 else 1280, intrinsics.width)
            for ((xs, ys) in listOf(0.1 to -0.2, -0.3 to 0.15, 0.0 to 0.0)) {
                // Sensor pixel, then buffer pixel.
                val bx = 0.32 * (k[0] * xs + k[2])
                val by = 0.32 * (k[1] * ys + k[3]) - 120
                // The buffer image turned clockwise by `rotation`, and the ray turned with it.
                val (u, v, xu, yu) = when (rotation) {
                    0 -> listOf(bx, by, xs, ys)
                    90 -> listOf(720 - by, bx, -ys, xs)
                    180 -> listOf(1280 - bx, 720 - by, -xs, -ys)
                    else -> listOf(by, 1280 - bx, ys, -xs)
                }
                assertEquals("rotation $rotation u", u, intrinsics.fx * xu + intrinsics.cx, 1e-3)
                assertEquals("rotation $rotation v", v, intrinsics.fy * yu + intrinsics.cy, 1e-3)
            }
        }
    }

    @Test
    fun `calibration is moved by the pre-correction array's origin`() {
        val withMargin = camera(preCorrection = SensorRect(-8, -6, 4008, 3006))
        val plain = checkNotNull(CameraIntrinsicsResolver.resolve(camera(), frame(0)))
        val shifted = checkNotNull(CameraIntrinsicsResolver.resolve(withMargin, frame(0)))
        // The active array starts 8 / 6 px into the pre-correction array: the centre moves by that much, scaled.
        assertEquals(plain.cx - 8 * 0.32f, shifted.cx, 1e-3f)
        assertEquals(plain.cy - 6 * 0.32f, shifted.cy, 1e-3f)
    }

    /**
     * An active array that does not start at the sensor origin. CameraX's transform maps the active
     * Rect, offset included, onto the buffer: here (20, 20) → buffer (0, −120) at scale 0.32.
     */
    @Test
    fun `a non-zero active array origin is counted once`() {
        val offsetActive = SensorRect(20, 20, 4020, 3020)
        val offsetTransform = listOf(0.32f, 0f, -6.4f, 0f, 0.32f, -126.4f, 0f, 0f, 1f)
        val facts = camera(calibration = listOf(3000f, 3100f, 2000f, 1500f, 0f), preCorrection = SensorRect(10, 10, 4030, 3030))
            .copy(activeArray = offsetActive)

        // Calibration centre (2000, 1500) from the pre-correction origin (10, 10): sensor (2010, 1510).
        val calibrated = checkNotNull(CameraIntrinsicsResolver.resolve(facts, frame(0, transform = offsetTransform)))
        assertEquals(636.8f, calibrated.cx, 1e-3f)
        assertEquals(356.8f, calibrated.cy, 1e-3f)

        // The estimate's centre is the active array's centre: the middle of the buffer.
        val estimated = checkNotNull(CameraIntrinsicsResolver.resolve(facts.copy(lensIntrinsicCalibration = null), frame(0, transform = offsetTransform)))
        assertEquals(640f, estimated.cx, 1e-3f)
        assertEquals(360f, estimated.cy, 1e-3f)

        // With no transform reported, the assumed centred fill is that same mapping.
        val assumed = checkNotNull(CameraIntrinsicsResolver.resolve(facts, frame(0, transform = null)))
        assertEquals(calibrated.cx, assumed.cx, 1e-3f)
        assertEquals(calibrated.cy, assumed.cy, 1e-3f)
    }

    @Test
    fun `without calibration the focal length is estimated from lens and sensor size`() {
        val intrinsics = checkNotNull(CameraIntrinsicsResolver.resolve(camera(calibration = null), frame(0)))
        assertEquals(IntrinsicsSource.FOCAL_LENGTH_ESTIMATE, intrinsics.source)
        // 4.8 mm over 6.4 mm across 4032 px, scaled by 0.32.
        assertEquals(4.8f / 6.4f * 4032 * 0.32f, intrinsics.fx, 1e-2f)
        assertEquals(2000 * 0.32f, intrinsics.cx, 1e-3f)
    }

    @Test
    fun `nothing to estimate from resolves to nothing`() {
        val bare = camera(calibration = null).copy(availableFocalLengthsMm = emptyList())
        assertNull(CameraIntrinsicsResolver.resolve(bare, frame(0)))
        assertNull(CameraIntrinsicsResolver.resolve(camera().copy(activeArray = null), frame(0)))
    }

    @Test
    fun `a missing transform assumes a centred fill and says so`() {
        val assumed = checkNotNull(CameraIntrinsicsResolver.resolve(camera(), frame(0, transform = null)))
        val reported = checkNotNull(CameraIntrinsicsResolver.resolve(camera(), frame(0)))
        assertTrue(assumed.bufferMappingAssumed)
        // The reported transform here is exactly a centred fill, so the numbers agree.
        assertEquals(reported.fx, assumed.fx, 1e-3f)
        assertEquals(reported.cy, assumed.cy, 1e-3f)
    }

    @Test
    fun `a transform that is not an axis-aligned crop is refused`() {
        val sheared = listOf(0.32f, 0.05f, 0f, 0f, 0.32f, -120f, 0f, 0f, 1f)
        assertNull(CameraIntrinsicsResolver.resolve(camera(), frame(0, transform = sheared)))
        val swapped = listOf(0f, 0.32f, 0f, 0.32f, 0f, -120f, 0f, 0f, 1f)
        val resolved = CameraIntrinsicsResolver.resolve(camera(), frame(0, transform = swapped))
        assertNotNull(resolved)
        // Axes swapped: buffer x follows sensor y.
        assertEquals(3100f * 0.32f, resolved!!.fx, 1e-3f)
    }
}
