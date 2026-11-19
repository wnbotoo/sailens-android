package com.sailens.guidance.world

import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.GeometryIssue
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalWorldGeometryTest {
    private val intrinsics = CameraIntrinsics(400f, 400f, 320f, 240f, 640, 480, IntrinsicsSource.CALIBRATION)
    private val geometry = CameraGeometry(
        frameTimeNanos = 1_000_000_000L,
        timeBasis = TimeBasis.CAMERA_REALTIME,
        intrinsics = intrinsics,
        gravityInCamera = Vec3(0f, 1f, 0f),
        gravityAgeNanos = 0L,
        phone = PhoneGeometrySettings(1.25f, HeightSource.PRESET),
        quality = GeometryQuality.Ok,
    )

    @Test
    fun `yaw to the right or move right and the landmark moves left`() {
        val frame = resolved(geometry)
        assertEquals(1_000_000_000L, frame.frameTimeNanos)
        val landmarkForward = 3.0
        val height = 1.25
        // Take the same world point relative to a level camera at its original, yawed, and translated poses.
        assertPoint(LocalGroundPoint(3.0, 0.0, 0.0), frame.locate(CameraVector(0.0, height, landmarkForward)))
        assertPoint(LocalGroundPoint(0.0, -3.0, 0.0), frame.locate(CameraVector(-landmarkForward, height, 0.0)))
        assertPoint(LocalGroundPoint(3.0, -1.0, 0.0), frame.locate(CameraVector(-1.0, height, landmarkForward)))
    }

    @Test
    fun `combined roll and pitch yield the planar axes and height offset`() {
        val frame = resolved(geometry.copy(gravityInCamera = Vec3(3f, 4f, 12f)))
        // A 3-4-5 horizontal component and a 5-12-13 total give analytic unit-axis components.
        assertVector(CameraVector(-36.0 / 65, -48.0 / 65, 5.0 / 13), frame.forward)
        assertVector(CameraVector(4.0 / 5, -3.0 / 5, 0.0), frame.right)
        assertVector(CameraVector(-3.0 / 13, -4.0 / 13, -12.0 / 13), frame.up)
        assertPoint(LocalGroundPoint(0.0, 0.0, 1.25), frame.locate(CameraVector(0.0, 0.0, 0.0)))
        val point = frame.pointOnPlane(forwardMeters = 3.025, rightMeters = 0.56)
        assertPoint(LocalGroundPoint(3.025, 0.56, 0.0), frame.locate(point))
    }

    @Test
    fun `both vertical directions reject just inside ten degrees`() {
        for (sign in listOf(-1f, 1f)) {
            val near = Vec3(0f, tan(Math.toRadians(9.99999)).toFloat(), sign)
            val far = Vec3(0f, tan(Math.toRadians(10.00001)).toFloat(), sign)
            rejected(LocalGroundFrame.Issue.VERTICAL_VIEW, geometry.copy(gravityInCamera = near))
            val frame = resolved(geometry.copy(gravityInCamera = far))
            assertEquals(1.0, frame.forward.dot(frame.forward), 1e-12)
        }
    }

    @Test
    fun `missing or unqualified geometry cannot resolve a nominal ground frame`() {
        rejected(LocalGroundFrame.Issue.FRAME_TIME, geometry.copy(frameTimeNanos = null))
        rejected(LocalGroundFrame.Issue.FRAME_TIME, geometry.copy(frameTimeNanos = 0L))
        rejected(LocalGroundFrame.Issue.TIME_BASIS, geometry.copy(timeBasis = TimeBasis.SOURCE_RECEIPT))
        rejected(LocalGroundFrame.Issue.INTRINSICS, geometry.copy(intrinsics = intrinsics.copy(fx = 0f)))
        rejected(LocalGroundFrame.Issue.INTRINSICS, geometry.copy(intrinsics = intrinsics.copy(cx = Float.NaN)))
        rejected(LocalGroundFrame.Issue.INTRINSICS, geometry.copy(intrinsics = intrinsics.copy(width = -1)))
        rejected(LocalGroundFrame.Issue.INTRINSICS, geometry.copy(intrinsics = intrinsics.copy(bufferMappingAssumed = true)))
        rejected(LocalGroundFrame.Issue.GRAVITY, geometry.copy(gravityInCamera = Vec3(0f, 0f, 0f)))
        rejected(LocalGroundFrame.Issue.GRAVITY, geometry.copy(gravityInCamera = Vec3(Float.NaN, 1f, 0f)))
        rejected(LocalGroundFrame.Issue.HEIGHT, geometry.copy(phone = geometry.phone.copy(heightMeters = Float.POSITIVE_INFINITY)))
        rejected(LocalGroundFrame.Issue.HEIGHT_SOURCE, geometry.copy(phone = PhoneGeometrySettings.DEFAULT))
        rejected(
            LocalGroundFrame.Issue.GEOMETRY_QUALITY,
            geometry.copy(quality = GeometryQuality.Degraded(setOf(GeometryIssue.STALE_GRAVITY))),
        )
    }

    @Test
    fun `forward boundaries use half-open ownership in Double precision`() {
        for (row in 0 until 60) {
            val edge = row / 10.0
            assertEquals(row * 40 + 20, LocalGroundGrid.indexOf(edge, 0.0))
            assertEquals(row * 40 + 20, LocalGroundGrid.indexOf(Math.nextUp(edge), 0.0))
            val below = if (row == 0) null else (row - 1) * 40 + 20
            assertEquals(below, LocalGroundGrid.indexOf(Math.nextDown(edge), 0.0))
        }
        assertEquals(59 * 40 + 20, LocalGroundGrid.indexOf(Math.nextDown(6.0), 0.0))
        assertNull(LocalGroundGrid.indexOf(6.0, 0.0))
    }

    @Test
    fun `right boundaries keep the final column distinct from the next row`() {
        for (col in 0 until 40) {
            val edge = (col - 20) / 10.0
            assertEquals(col, LocalGroundGrid.indexOf(0.0, edge))
            assertEquals(col, LocalGroundGrid.indexOf(0.0, Math.nextUp(edge)))
            val below = if (col == 0) null else col - 1
            assertEquals(below, LocalGroundGrid.indexOf(0.0, Math.nextDown(edge)))
        }
        assertEquals(39, LocalGroundGrid.indexOf(0.0, Math.nextDown(2.0)))
        assertEquals(2_399, LocalGroundGrid.indexOf(Math.nextDown(6.0), Math.nextDown(2.0)))
        assertEquals(20, LocalGroundGrid.indexOf(-0.0, -0.0))
        assertNull(LocalGroundGrid.indexOf(0.0, 2.0))
    }

    @Test
    fun `grid cell bounds and rejection use the same physical domain`() {
        val last = checkNotNull(LocalGroundGrid.boundsAt(59, 39))
        assertEquals(LocalGroundCellBounds(5.9, 6.0, 1.9, 2.0), last)
        assertEquals(2_399, LocalGroundGrid.indexOf(last.centreForward, last.centreRight))
        assertNull(LocalGroundGrid.boundsAt(60, 39))
        assertNull(LocalGroundGrid.boundsAt(59, 40))
        assertNull(LocalGroundGrid.indexOf(Double.NaN, 0.0))
        assertNull(LocalGroundGrid.indexOf(0.0, Double.NEGATIVE_INFINITY))
    }

    private fun resolved(geometry: CameraGeometry): LocalGroundFrame =
        (LocalGroundFrame.resolve(geometry) as LocalGroundFrame.Result.Resolved).frame

    private fun rejected(issue: LocalGroundFrame.Issue, geometry: CameraGeometry) {
        assertEquals(LocalGroundFrame.Result.Unavailable(geometry, issue), LocalGroundFrame.resolve(geometry))
    }

    private fun assertPoint(expected: LocalGroundPoint, actual: LocalGroundPoint) {
        assertEquals(expected.forward, actual.forward, 1e-12)
        assertEquals(expected.right, actual.right, 1e-12)
        assertEquals(expected.up, actual.up, 1e-12)
    }

    private fun assertVector(expected: CameraVector, actual: CameraVector) {
        assertEquals(expected.x, actual.x, 1e-12)
        assertEquals(expected.y, actual.y, 1e-12)
        assertEquals(expected.z, actual.z, 1e-12)
    }
}
