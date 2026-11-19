package com.sailens.guidance.world

import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldProjectionTest {
    private val height = 1.25
    private val geometry = CameraGeometry(
        1L, TimeBasis.CAMERA_REALTIME,
        CameraIntrinsics(400f, 400f, 320f, 240f, 640, 480, IntrinsicsSource.CALIBRATION),
        Vec3(3f, 4f, 0f), 0L, PhoneGeometrySettings(height.toFloat(), HeightSource.PRESET), GeometryQuality.Ok,
    )
    private val frame = (LocalGroundFrame.resolve(geometry) as LocalGroundFrame.Result.Resolved).frame
    private val projection = WorldProjection(frame)

    @Test
    fun `rolled camera envelope covers the far end of a wide detection`() {
        val bottom = 427.0
        val left = 250.0
        val right = 530.0
        val tolerance = 0.10
        val rotationTolerance = 2.0
        val image = ImageFootprint(Bound(left, right), Bound(340.0, bottom))
        val bounds = checkNotNull(projection.contactEnvelope(image, Bound(bottom), height, tolerance, rotationTolerance, 3.0))
        val tilt = Math.toRadians(rotationTolerance)
        for (index in 0..32) {
            val u = left + (right - left) * index / 32
            val ray = CameraVector((u - 320.0) / 400.0, (bottom - 240.0) / 400.0, 1.0)
            for (degrees in listOf(-tilt, 0.0, tilt)) {
                val grav = CameraVector(.6 * cos(degrees) - .8 * sin(degrees), .8 * cos(degrees) + .6 * sin(degrees), 0.0)
                for (actualHeight in listOf(height * (1.0 - tolerance), height * (1.0 + tolerance))) {
                    val point = frame.locate(ray * (actualHeight / grav.dot(ray)))
                    assertTrue(point.forward in bounds.x.low..bounds.x.high)
                    assertTrue(point.right in bounds.y.low..bounds.y.high)
                }
            }
        }
        assertTrue("the left edge reaches farther than the centre ray", bounds.x.high > 5.0)
    }

    @Test
    fun `one end near the horizon rejects localisation even if the centre is well below it`() {
        val nearHorizon = ImageFootprint(Bound(43.0, 620.0), Bound(225.0, 436.0))
        assertNull(projection.contactEnvelope(nearHorizon, Bound(436.0), height, .1, 2.0, 3.0))
        val centreOnly = nearHorizon.copy(x = Bound(331.5))
        assertNotNull(projection.contactEnvelope(centreOnly, Bound(436.0), height, .1, 2.0, 3.0))
    }
}
