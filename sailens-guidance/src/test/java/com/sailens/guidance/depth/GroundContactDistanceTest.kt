package com.sailens.guidance.depth

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.GeometryIssue
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import com.sailens.vision.taxonomy.CityscapesTaxonomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The ground-contact maths and validity checks of M3a (§8) on a synthetic pinhole camera: the
 * class-map intrinsics of the M2a scenarios, a known pitch and phone height, and boxes projected
 * from known ground points.
 */
class GroundContactDistanceTest {

    private val estimator = GroundContactDistanceEstimator(CityscapesNavigationSemantics)
    private val k = CameraIntrinsics(449.625f, 449.625f, 180f, 320f, 360, 640, IntrinsicsSource.CALIBRATION)

    private fun geometry(
        pitchDeg: Double,
        heightM: Float = 1.3f,
        source: HeightSource = HeightSource.PRESET,
        quality: GeometryQuality = GeometryQuality.Ok,
    ): CameraGeometry {
        val t = Math.toRadians(pitchDeg)
        return CameraGeometry(
            frameTimeNanos = 1, timeBasis = TimeBasis.CAMERA_REALTIME, intrinsics = k,
            gravityInCamera = Vec3(0f, cos(t).toFloat(), sin(t).toFloat()), gravityAgeNanos = 0,
            phone = PhoneGeometrySettings(heightM, source), quality = quality,
        )
    }

    /** Normalised image y of a point [aboveGroundM] above the ground, [distanceM] ahead, for a camera at [heightM] pitched down [pitchDeg]. */
    private fun imageY(distanceM: Double, pitchDeg: Double, heightM: Double = 1.3, aboveGroundM: Double = 0.0): Float {
        val t = Math.toRadians(pitchDeg)
        val below = heightM - aboveGroundM
        // Camera axes: forward-on-ground = (0, -sin t, cos t), down = (0, cos t, sin t).
        val y = -distanceM * sin(t) + below * cos(t)
        val z = distanceM * cos(t) + below * sin(t)
        return ((k.cy + k.fy * y / z) / k.height).toFloat()
    }

    /** An upright object of [objectHeightM] standing [distanceM] ahead; [visibleFromM] cuts the box bottom there. */
    private fun box(
        distanceM: Double,
        pitchDeg: Double,
        heightM: Double = 1.3,
        objectHeightM: Double = 1.7,
        visibleFromM: Double = 0.0,
        x: Float = 0.45f,
        width: Float = 0.1f,
    ): NormalizedRect {
        val bottom = imageY(distanceM, pitchDeg, heightM, visibleFromM)
        val top = imageY(distanceM, pitchDeg, heightM, objectHeightM).coerceAtLeast(0f)
        return NormalizedRect(x, top, width, bottom - top)
    }

    private fun estimate(
        box: NormalizedRect,
        geometry: CameraGeometry?,
        category: ObstacleCategory = ObstacleCategory.PERSON,
        others: List<NormalizedRect> = emptyList(),
        segmentation: SegmentationMask? = null,
        config: GroundContactDistanceConfig = GroundContactDistanceConfig(),
    ) = estimator.estimate(box, category, DistanceFrame(geometry, others, segmentation, config))

    private fun map(classId: Int) = SegmentationMask(360, 640, IntArray(360 * 640) { classId })

    @Test
    fun `a contact projects back to its own distance at every pitch`() {
        for (pitch in listOf(5.0, 10.0, 17.0, 25.0, 40.0)) for (d in listOf(1.0, 1.5, 2.5, 4.0, 8.0)) {
            val e = estimate(box(d, pitch), geometry(pitch))
            if (e.validity == ContactValidity.CLIPPED || e.validity == ContactValidity.NEAR_HORIZON) continue
            assertEquals("pitch $pitch d $d: ${e.validity}", ContactValidity.VALID, e.validity)
            assertEquals("pitch $pitch d $d", d, e.pointM!!.toDouble(), d * 1e-3)
            assertTrue(e.lowerM!! < d && d < e.upperM!!)
        }
    }

    @Test
    fun `the interval holds the truth under the height and gravity errors it allows`() {
        for (pitch in listOf(10.0, 17.0, 25.0)) for (d in listOf(1.2, 2.0, 3.5, 6.0)) {
            for (heightError in listOf(-0.149, 0.0, 0.149)) for (gravityError in listOf(-1.99, 0.0, 1.99)) {
                val trueHeight = 1.3 * (1 + heightError)
                val b = box(d, pitch + gravityError, trueHeight)
                if (b.maxY >= 0.99f) continue
                val e = estimate(b, geometry(pitch))
                if (e.validity == ContactValidity.NEAR_HORIZON) continue
                assertTrue("pitch $pitch d $d h $trueHeight g $gravityError: ${e.lowerM} .. ${e.upperM}", e.lowerM!! <= d && d <= e.upperM!!)
            }
        }
    }

    @Test
    fun `a calibrated height narrows the interval`() {
        val b = box(3.0, 17.0)
        val preset = estimate(b, geometry(17.0, source = HeightSource.PRESET))
        val calibrated = estimate(b, geometry(17.0, source = HeightSource.CALIBRATED))
        assertTrue(calibrated.lowerM!! > preset.lowerM!!)
        assertTrue(calibrated.upperM!! < preset.upperM!!)
        assertEquals(preset.pointM!!, calibrated.pointM!!, 1e-6f)
    }

    @Test
    fun `a contact at or above the horizon has no distance`() {
        val far = estimate(box(60.0, 10.0), geometry(10.0))
        assertEquals(ContactValidity.NEAR_HORIZON, far.validity)
        assertNull(far.lowerM)
        val above = estimate(NormalizedRect(0.45f, 0.1f, 0.1f, 0.1f), geometry(0.0))
        assertEquals(ContactValidity.NEAR_HORIZON, above.validity)
    }

    @Test
    fun `a box reaching the bottom edge is clipped, with the edge's distance kept for the trace`() {
        val e = estimate(NormalizedRect(0.4f, 0.5f, 0.2f, 0.4999f), geometry(25.0))
        assertEquals(ContactValidity.CLIPPED, e.validity)
        assertNotNull(e.lowerM)
    }

    @Test
    fun `a nearer box over the contact occludes it, a farther or beside one does not`() {
        val person = box(4.0, 17.0)
        val bench = NormalizedRect(0.40f, person.maxY - 0.05f, 0.2f, 0.12f)
        assertEquals(ContactValidity.OCCLUDED, estimate(person, geometry(17.0), others = listOf(person, bench)).validity)
        val beside = bench.copy(x = 0.75f)
        assertEquals(ContactValidity.VALID, estimate(person, geometry(17.0), others = listOf(beside)).validity)
        val behind = NormalizedRect(0.40f, person.maxY - 0.2f, 0.2f, 0.15f)
        assertEquals(ContactValidity.VALID, estimate(person, geometry(17.0), others = listOf(behind)).validity)
        // The box itself is in the frame's list; it does not occlude itself.
        assertEquals(ContactValidity.VALID, estimate(person, geometry(17.0), others = listOf(person)).validity)
    }

    @Test
    fun `no ground below the contact makes it unseen`() {
        val person = box(3.0, 17.0)
        assertEquals(ContactValidity.GROUND_UNSEEN, estimate(person, geometry(17.0), segmentation = map(CityscapesTaxonomy.BUILDING)).validity)
        assertEquals(ContactValidity.VALID, estimate(person, geometry(17.0), segmentation = map(CityscapesTaxonomy.SIDEWALK)).validity)
        assertEquals(ContactValidity.VALID, estimate(person, geometry(17.0), segmentation = map(CityscapesTaxonomy.TERRAIN)).validity)
    }

    @Test
    fun `a box whose implied height does not fit its class is implausible`() {
        val g = geometry(17.0)
        // Legs behind a 1 m wall: the box ends at the wall top, reads farther and far too tall.
        val cut = box(5.0, 17.0, visibleFromM = 1.0)
        assertEquals(ContactValidity.IMPLAUSIBLE_BOX, estimate(cut, g).validity)
        // A box that stops at knee height: too short for a person standing there.
        assertEquals(ContactValidity.IMPLAUSIBLE_BOX, estimate(box(3.0, 17.0, objectHeightM = 0.4), g).validity)
        // The same short box as a static obstacle has no size to check.
        assertEquals(ContactValidity.VALID, estimate(box(3.0, 17.0, objectHeightM = 0.4), g, ObstacleCategory.STATIC_OBSTACLE).validity)
        // A child, and a bus, fit.
        assertEquals(ContactValidity.VALID, estimate(box(3.0, 17.0, objectHeightM = 1.0), g).validity)
        assertEquals(ContactValidity.VALID, estimate(box(8.0, 17.0, objectHeightM = 3.2), g, ObstacleCategory.VEHICLE).validity)
        // A box whose top is cut by the frame is not judged.
        val tall = box(1.6, 40.0)
        assertEquals(0f, tall.y)
        assertEquals(ContactValidity.VALID, estimate(tall, geometry(40.0)).validity)
    }

    @Test
    fun `geometry that cannot be trusted gives no estimate`() {
        val b = box(3.0, 17.0)
        assertEquals(ContactValidity.NO_GEOMETRY, estimate(b, null).validity)
        assertEquals(ContactValidity.NO_GEOMETRY, estimate(b, geometry(17.0, quality = GeometryQuality.Unavailable(setOf(GeometryIssue.NO_GRAVITY)))).validity)
        assertEquals(ContactValidity.NO_GEOMETRY, estimate(b, geometry(17.0, quality = GeometryQuality.Degraded(setOf(GeometryIssue.STALE_GRAVITY)))).validity)
        val estimated = geometry(17.0, quality = GeometryQuality.Degraded(setOf(GeometryIssue.ESTIMATED_INTRINSICS, GeometryIssue.UNMEASURED_ALIGNMENT)))
        assertEquals(ContactValidity.VALID, estimate(b, estimated).validity)
    }
}
