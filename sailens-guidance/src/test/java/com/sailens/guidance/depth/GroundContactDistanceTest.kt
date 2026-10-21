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
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

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
        rollDeg: Double = 0.0,
    ): CameraGeometry {
        val t = Math.toRadians(pitchDeg)
        val r = Math.toRadians(rollDeg)
        return CameraGeometry(
            frameTimeNanos = 1, timeBasis = TimeBasis.CAMERA_REALTIME, intrinsics = k,
            gravityInCamera = Vec3((cos(t) * sin(r)).toFloat(), (cos(t) * cos(r)).toFloat(), sin(t).toFloat()), gravityAgeNanos = 0,
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
        // Legs behind a 1 m wall: the box ends at the wall top, so it reads farther and taller. Its
        // contact is barely below the horizon, where the allowed gravity error spans heights from
        // plausible to not, so the check cannot reject it. Reading farther than the truth, it never
        // claims the obstacle is nearer than it is (here: FAR, raising nothing).
        val cut = estimate(box(5.0, 17.0, visibleFromM = 1.0), g)
        assertTrue("reads farther than the truth: ${cut.lowerM}", cut.lowerM!! > 5.0)
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
    fun `the height check allows the same gravity error as the distance interval`() {
        // Review round 1, P2: camera at 1 m, a 0.7 m object 6 m ahead, 25 degrees down, gravity off
        // by just under the 2 degree tolerance. The interval still holds the truth, so the contact
        // must not be thrown out as implausible either.
        val b = box(6.0, 25.0, heightM = 1.0, objectHeightM = 0.7)
        for (error in listOf(-1.99, 0.0, 1.99)) {
            val e = estimate(b, geometry(25.0 + error, heightM = 1.0f))
            assertEquals("gravity error $error", ContactValidity.VALID, e.validity)
            assertTrue("gravity error $error: ${e.lowerM} .. ${e.upperM}", e.lowerM!! <= 6.0 && 6.0 <= e.upperM!!)
        }
        // Outside every allowed error the check still bites: a person box that ends at knee height.
        val knee = box(3.0, 25.0, heightM = 1.0, objectHeightM = 0.35)
        for (error in listOf(-1.99, 0.0, 1.99)) {
            assertEquals("gravity error $error", ContactValidity.IMPLAUSIBLE_BOX, estimate(knee, geometry(25.0 + error, heightM = 1.0f)).validity)
        }
    }

    /** Angle below the horizon of the ray through normalised ([x], [y]) for gravity [g]. */
    private fun depression(x: Float, y: Float, g: DoubleArray): Double {
        val rx = (x * k.width - k.cx).toDouble() / k.fx
        val ry = (y * k.height - k.cy).toDouble() / k.fy
        return asin((g[0] * rx + g[1] * ry + g[2]) / sqrt(rx * rx + ry * ry + 1.0))
    }

    /** A gravity within [delta] of [g], uniform over the cap (interior included), or on its rim. */
    private fun tilted(g: DoubleArray, delta: Double, random: Random, rim: Boolean): DoubleArray {
        val seed = if (abs(g[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        val along = seed[0] * g[0] + seed[1] * g[1] + seed[2] * g[2]
        val u = DoubleArray(3) { seed[it] - along * g[it] }.let { v -> val n = sqrt(v.sumOf { it * it }); DoubleArray(3) { v[it] / n } }
        val w = doubleArrayOf(g[1] * u[2] - g[2] * u[1], g[2] * u[0] - g[0] * u[2], g[0] * u[1] - g[1] * u[0])
        val angle = if (rim) delta else delta * sqrt(random.nextDouble())
        val phi = 2 * Math.PI * random.nextDouble()
        return DoubleArray(3) { cos(angle) * g[it] + sin(angle) * (cos(phi) * u[it] + sin(phi) * w[it]) }
    }

    @Test
    fun `a box is implausible only if no allowed gravity and height make it fit`() {
        // Review round 2, P2: the rejection must hold for every gravity in the tolerance cone --
        // the rim and the interior -- and every allowed phone height. Searched by sampling: any
        // sample that fits while the estimator rejected the box is a counterexample.
        val ranges = mapOf(ObstacleCategory.PERSON to 0.7..2.4, ObstacleCategory.BICYCLE to 0.6..2.2, ObstacleCategory.VEHICLE to 0.9..4.5)
        val config = GroundContactDistanceConfig()
        val delta = Math.toRadians(config.gravityToleranceDeg.toDouble())
        val random = Random(20261021)
        data class Case(val pitch: Double, val roll: Double, val h: Float, val box: NormalizedRect, val category: ObstacleCategory)
        val reviewCases = listOf(
            Case(25.0, 17.0, 1.0f, NormalizedRect(0.59f, 0.3890047f, 0.1f, 0.2609953f), ObstacleCategory.PERSON),
            Case(81.09889, 0.0, 1.0f, NormalizedRect(0.45f, 0.594f, 0.1f, 0.006f), ObstacleCategory.PERSON),
        )
        val randomCases = List(4000) {
            val top = random.nextFloat() * 0.85f + 0.02f
            val bottom = top + 0.005f + random.nextFloat() * (0.97f - top)
            Case(
                pitch = random.nextDouble(-5.0, 85.0), roll = random.nextDouble(-30.0, 30.0), h = random.nextFloat() * 0.7f + 0.9f,
                box = NormalizedRect(random.nextFloat() * 0.8f + 0.05f, top, 0.1f, bottom - top),
                category = ranges.keys.random(random),
            )
        }
        var rejected = 0
        for (case in reviewCases + randomCases) {
            val geometry = geometry(case.pitch, heightM = case.h, rollDeg = case.roll)
            val e = estimate(case.box, geometry, case.category, config = config)
            if (e.validity != ContactValidity.IMPLAUSIBLE_BOX) continue
            rejected++
            val g0 = geometry.gravityInCamera!!.let { doubleArrayOf(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
            val range = ranges.getValue(case.category)
            repeat(600) { i ->
                val g = tilted(g0, delta, random, rim = i % 3 == 0)
                val b = depression(case.box.centerX, case.box.maxY, g)
                if (b <= 0.0) return@repeat
                val ratio = tan(depression(case.box.centerX, case.box.y, g)) / tan(b)
                for (h in listOf(case.h * (1 - config.presetHeightTolerance), case.h, case.h * (1 + config.presetHeightTolerance))) {
                    val implied = h * (1 - ratio)
                    assertTrue("$case rejected, but gravity ${g.toList()} and height $h give $implied", implied !in range)
                }
            }
        }
        // The two review cases fit within the tolerances, so they must not be rejected.
        for (case in reviewCases) {
            val e = estimate(case.box, geometry(case.pitch, heightM = case.h, rollDeg = case.roll), case.category, config = config)
            assertTrue("$case: ${e.validity}", e.validity != ContactValidity.IMPLAUSIBLE_BOX)
        }
        assertTrue("the search must exercise rejections: $rejected", rejected >= 200)
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
