package com.sailens.guidance.geometry

import com.sailens.guidance.sim.Box
import com.sailens.guidance.sim.Pose
import com.sailens.guidance.sim.SceneClasses
import com.sailens.guidance.sim.Scenarios
import com.sailens.guidance.sim.SyntheticCamera
import com.sailens.guidance.sim.Vec
import com.sailens.guidance.sim.World
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

/**
 * The ground fit (docs/geometry.md §6) on synthetic disparity rendered from simulator scenes: planes, a curb,
 * a drop, walls, an indoor floor the sem model would call building, noise, an inverted model.
 * Disparity is `a/Z + b` with a = 10, b = 0.2, so on ground at 1.3 m the fit must find A = 10/1.3, t = 0.2.
 */
class GroundFitterTest {

    private val camera = SyntheticCamera()
    private val aw = 720
    private val ah = 1280
    private val gridW = camera.mapWidth
    private val gridH = camera.mapHeight
    private val scale = aw.toFloat() / camera.mapWidth
    private val intrinsics = CameraIntrinsics(camera.focal * scale, camera.focal * scale, camera.cx * scale, camera.cy * scale, aw, ah, IntrinsicsSource.CALIBRATION)

    private val street = Scenarios.straightClearPath.frames[0].world
    private val pose = Pose(1.3f, 25f)

    private fun gravity(pose: Pose, rollDeg: Double = 0.0): Vec3 {
        val t = Math.toRadians(pose.pitchDownDeg.toDouble())
        val r = Math.toRadians(rollDeg)
        return Vec3((sin(r) * cos(t)).toFloat(), (cos(r) * cos(t)).toFloat(), sin(t).toFloat())
    }

    private fun disparity(
        world: World,
        pose: Pose = this.pose,
        policy: ResizePolicy = ResizePolicy.CROP_KEEP_ASPECT,
        mw: Int = 294,
        mh: Int = 518,
        noise: Double = 0.0,
        asDepth: Boolean = false,
        a: Double = 10.0,
        b: Double = 0.2,
    ): DisparityMap {
        val transform = DepthInputTransform.create(aw, ah, mw, mh, policy)
        var values = renderDisparity(camera, pose, world, mw, mh, aw, ah, { x, y -> transform.toAnalysisX(x) to transform.toAnalysisY(y) }, a = a, b = b, noise = noise)
        // A model that outputs depth: Z = a / (d - b), sky capped far away.
        if (asDepth) values = FloatArray(values.size) { i -> if (values[i] > 0.2001f) (10f / (values[i] - 0.2f)).coerceAtMost(200f) else 200f }
        return DisparityMap(mw, mh, values, transform)
    }

    private fun fit(map: DisparityMap, pose: Pose = this.pose, rollDeg: Double = 0.0) =
        GroundFitter().fit(map, intrinsics, gravity(pose, rollDeg), gridW, gridH)

    /** Analysis pixel of world point (x, y, z) seen from [pose], and its q. */
    private fun project(pose: Pose, x: Double, y: Double, z: Double): Triple<Float, Float, Float> {
        val rel = Vec(x, y, z) - pose.position
        val zc = rel dot pose.forward
        val u = (intrinsics.cx + intrinsics.fx * (rel dot pose.right) / zc).toFloat()
        val v = (intrinsics.cy + intrinsics.fy * (rel dot pose.down) / zc).toFloat()
        val g = gravity(pose)
        val q = g.x * (u - intrinsics.cx) / intrinsics.fx + g.y * (v - intrinsics.cy) / intrinsics.fy + g.z
        return Triple(u, v, q)
    }

    private fun cell(u: Float, v: Float) = (u / aw * gridW).toInt() to (v / ah * gridH).toInt()

    private fun heightAt(observation: GroundObservation, map: DisparityMap, pose: Pose, x: Double, y: Double, z: Double): Float {
        val (u, v, q) = project(pose, x, y, z)
        return checkNotNull(observation.heightAboveGroundM(map.sampleAt(u, v)!!, q, pose.heightM)) { "no height at $x,$y,$z" }
    }

    @Test
    fun `an open street is ground, with the scale and shift the model used`() {
        val observation = fit(disparity(street))
        assertTrue(observation.isValid)
        assertEquals(10f / 1.3f, observation.disparityScale, 0.01f * 10f / 1.3f)
        assertEquals(0.2f, observation.disparityShift, 0.005f)
        assertTrue("inliers ${observation.inlierRatio}", observation.inlierRatio > 0.95f)
        assertTrue(observation.planeAgreesWithGravity)
        assertTrue(observation.confidence > 0.9f)
        // Every seen cell below the horizon is ground; nothing is below it.
        val (_, horizonRow) = cell(0f, project(pose, 0.0, 0.0, 1e6).second)
        var belowHorizon = 0
        var ground = 0
        // Cells within minQ of the horizon are left out of the ground by design: start past them.
        for (gy in horizonRow + 20 until gridH) for (gx in 0 until gridW) {
            belowHorizon++
            if (observation.groundMask.get(gx, gy)) ground++
        }
        assertTrue("ground $ground of $belowHorizon", ground > 0.97 * belowHorizon)
        assertEquals(0, observation.belowGroundMask.countTrue())
    }

    @Test
    fun `every resize policy finds the same ground, and what a crop cut away stays unknown`() {
        val stretch = fit(disparity(street, policy = ResizePolicy.STRETCH, mw = 392, mh = 518))
        val crop = fit(disparity(street, policy = ResizePolicy.CROP_KEEP_ASPECT, mw = 392, mh = 392))
        val pad = fit(disparity(street, policy = ResizePolicy.PAD_KEEP_ASPECT, mw = 518, mh = 518))
        for ((name, o) in listOf("stretch" to stretch, "crop" to crop, "pad" to pad)) {
            assertTrue(name, o.isValid)
            assertEquals(name, 10f / 1.3f, o.disparityScale, 0.01f * 10f / 1.3f)
            assertEquals(name, 0.2f, o.disparityShift, 0.005f)
        }
        // The square crop sees the middle 720x720 only: the bottom 280 analysis rows are unknown.
        val firstUnseenRow = ((1280 - 280) / 1280f * gridH).toInt() + 1
        for (gy in firstUnseenRow until gridH) for (gx in 0 until gridW) {
            assertFalse(crop.groundMask.get(gx, gy) || crop.aboveGroundMask.get(gx, gy) || crop.belowGroundMask.get(gx, gy))
        }
        // Where all three saw the scene, they label it alike.
        var agree = 0
        var total = 0
        for (gy in gridH * 3 / 8 until firstUnseenRow - 1) for (gx in 0 until gridW) {
            total++
            if (stretch.groundMask.get(gx, gy) == crop.groundMask.get(gx, gy) && crop.groundMask.get(gx, gy) == pad.groundMask.get(gx, gy)) agree++
        }
        assertTrue("agree $agree of $total", agree > 0.98 * total)
    }

    @Test
    fun `a 15 cm curb down is not ground, and its height comes out`() {
        val world = Scenarios.curbAndDrop.frames[0].world
        val map = disparity(world)
        val observation = fit(map)
        assertTrue(observation.isValid)
        assertEquals(-0.15f, heightAt(observation, map, pose, 0.0, -0.15, 6.0), 0.02f)
        assertEquals(0f, heightAt(observation, map, pose, 0.0, 0.0, 2.5), 0.02f)
        val (u, v, _) = project(pose, 0.0, -0.15, 6.0)
        val (gx, gy) = cell(u, v)
        assertFalse("the road beyond the curb is not ground level", observation.groundMask.get(gx, gy))
    }

    @Test
    fun `a 1·5 m drop is below ground`() {
        val world = Scenarios.curbAndDrop.frames[40].world
        val map = disparity(world)
        val observation = fit(map)
        assertTrue(observation.isValid)
        // Far enough out that the ray clears the edge at 4 m.
        assertEquals(-1.5f, heightAt(observation, map, pose, 0.0, -1.5, 12.0), 0.05f)
        val (u, v, _) = project(pose, 0.0, -1.5, 12.0)
        val (gx, gy) = cell(u, v)
        assertTrue(observation.belowGroundMask.get(gx, gy))
    }

    @Test
    fun `a wall and a person stand above ground`() {
        val world = Scenarios.partiallyOccludedPerson.frames[0].world
        val map = disparity(world)
        val observation = fit(map)
        assertTrue(observation.isValid)
        // On the wall's face, half way up, and on the person's head above it.
        assertEquals(0.5f, heightAt(observation, map, pose, -1.0, 0.5, 5.0), 0.05f)
        val (u, v, _) = project(pose, -1.0, 0.5, 5.0)
        val (gx, gy) = cell(u, v)
        assertTrue(observation.aboveGroundMask.get(gx, gy))
        assertEquals(1.5f, heightAt(observation, map, pose, -0.25, 1.5, 5.8), 0.08f)
    }

    @Test
    fun `an indoor floor is ground whatever sem calls it`() {
        // The floor sem reads as building (ground_no_ground's indoor phase): depth does not care.
        val indoor = Scenarios.groundNoGround.frames[25].world
        val observation = fit(disparity(indoor))
        assertTrue(observation.isValid)
        assertTrue("inliers ${observation.inlierRatio}", observation.inlierRatio > 0.8f)
        val (u, v, _) = project(pose, 0.0, 0.0, 3.0)
        val (gx, gy) = cell(u, v)
        assertTrue(observation.groundMask.get(gx, gy))
    }

    /**
     * Walking up to a 1.5 m drop and on until only its floor is in view.
     * The floor is a bigger, perfectly fittable plane; it must never become "the ground".
     */
    @Test
    fun `approaching a drop never takes its floor for the ground`() {
        val drop = Scenarios.curbAndDrop.frames[40].world
        val fitter = GroundFitter()
        var valid = 0
        for (i in 0 until 50) {
            val walked = Pose(1.3f, 25f, walkedM = i * 0.08f)
            val observation = fitter.fit(disparity(drop, pose = walked), intrinsics, gravity(walked), gridW, gridH)
            val where = "walked ${walked.walkedM} m (edge ${4f - walked.walkedM} m ahead)"
            if (observation.isValid) {
                valid++
                assertEquals(where, 10f / 1.3f, observation.disparityScale, 0.01f * 10f / 1.3f)
            }
            // While the sidewalk spans enough depth to pin a line, the fit is the sidewalk.
            if (walked.walkedM <= 2.8f) assertTrue(where, observation.isValid)
            // Once only the drop's floor is in view, the support is unknown.
            if (walked.walkedM >= 3.3f) assertEquals(where, GroundFitValidity.SUPPORT_SWITCH, observation.validity)
        }
        assertTrue(valid >= 35)
    }

    @Test
    fun `only seeing both levels again, or a new session, ends the hold`() {
        val drop = Scenarios.curbAndDrop.frames[40].world
        val atEdge = Pose(1.3f, 25f, walkedM = 3.6f)
        val back = Pose(1.3f, 25f, walkedM = 2.0f)
        fun approach(fitter: GroundFitter) {
            // Up to the last fit that still sees the sidewalk under the user.
            for (i in 0..36) {
                val walked = Pose(1.3f, 25f, walkedM = i * 0.08f)
                fitter.fit(disparity(drop, pose = walked), intrinsics, gravity(walked), gridW, gridH)
            }
        }
        val fitter = GroundFitter()
        approach(fitter)
        assertEquals(GroundFitValidity.SUPPORT_SWITCH, fitter.fit(disparity(drop, pose = atEdge), intrinsics, gravity(atEdge), gridW, gridH).validity)
        // Stepping back: sidewalk and drop together again, the sidewalk under the user.
        val again = fitter.fit(disparity(drop, pose = back), intrinsics, gravity(back), gridW, gridH)
        assertTrue(again.isValid)
        assertEquals(10f / 1.3f, again.disparityScale, 0.01f * 10f / 1.3f)

        // Standing 0.4 m from the edge, the same depth frame over and over (a hold that ran out
        // after 30 fits would take the drop's floor for the ground on the 31st). No
        // number of frames shows the user stepped down.
        val standing = GroundFitter()
        approach(standing)
        val atEdgeFrame = disparity(drop, pose = atEdge)
        repeat(60) { k ->
            assertEquals("fit ${k + 1} at the edge", GroundFitValidity.SUPPORT_SWITCH, standing.fit(atEdgeFrame, intrinsics, gravity(atEdge), gridW, gridH).validity)
        }

        // A new session forgets the drop.
        val fresh = GroundFitter()
        approach(fresh)
        fresh.reset()
        assertTrue(fresh.fit(disparity(drop, pose = atEdge), intrinsics, gravity(atEdge), gridW, gridH).isValid)
    }

    /**
     * Relative-depth models output `a/Z + b` with a and b unknown and free per frame: no choice of
     * them may change a label.
     */
    @Test
    fun `labels do not depend on the model's scale and shift`() {
        val scenes = listOf(street, Scenarios.curbAndDrop.frames[0].world, Scenarios.curbAndDrop.frames[40].world, Scenarios.partiallyOccludedPerson.frames[0].world)
        for (world in scenes) {
            val reference = fit(disparity(world))
            for ((a, b) in listOf(10.0 to 3.0, 10.0 to 10.0, 1.0 to 0.05, 50.0 to 5.0, 10.0 to -0.15)) {
                val observation = fit(disparity(world, a = a, b = b))
                assertTrue("a=$a b=$b", observation.isValid)
                assertEquals("a=$a b=$b", (a / 1.3).toFloat(), observation.disparityScale, (0.01 * a / 1.3).toFloat())
                assertEquals("a=$a b=$b", b.toFloat(), observation.disparityShift, (0.005 * a).toFloat())
                assertEquals("a=$a b=$b ground", reference.groundMask, observation.groundMask)
                assertEquals("a=$a b=$b above", reference.aboveGroundMask, observation.aboveGroundMask)
                assertEquals("a=$a b=$b below", reference.belowGroundMask, observation.belowGroundMask)
            }
        }
    }

    @Test
    fun `noise of a few percent still fits`() {
        val observation = fit(disparity(street, noise = 0.03))
        assertTrue(observation.isValid)
        assertTrue("inliers ${observation.inlierRatio}", observation.inlierRatio > 0.6f)
        assertEquals(10f / 1.3f, observation.disparityScale, 0.03f * 10f / 1.3f)
    }

    @Test
    fun `a model that outputs depth is refused`() {
        assertEquals(GroundFitValidity.INVERSE_OUTPUT, fit(disparity(street, asDepth = true)).validity)
    }

    @Test
    fun `a wall filling the view is no ground`() {
        val wall = World(ground = street.ground, boxes = listOf(Box(SceneClasses.WALL, -30.0, 30.0, 1.0, 1.2, 10.0)))
        val level = Pose(1.3f, 0f)
        assertEquals(GroundFitValidity.NO_GROUND, fit(disparity(wall, pose = level, noise = 0.02), pose = level).validity)
    }

    @Test
    fun `pointing straight down is too narrow a view to fit`() {
        val down = Pose(1.3f, 89f)
        assertEquals(GroundFitValidity.DEGENERATE_VIEW, fit(disparity(street, pose = down), pose = down).validity)
    }

    @Test
    fun `gravity that disagrees with the ground is noticed`() {
        val map = disparity(street)
        assertTrue(fit(map).planeAgreesWithGravity)
        val wrong = fit(map, rollDeg = 15.0)
        assertFalse(wrong.planeAgreesWithGravity)
        assertTrue(wrong.confidence < fit(map).confidence)
    }

    @Test
    fun `heights read nothing from an invalid fit`() {
        val observation = fit(disparity(street, asDepth = true))
        assertEquals(null, observation.heightAboveGroundM(1f, 0.5f, 1.3f))
    }
}
