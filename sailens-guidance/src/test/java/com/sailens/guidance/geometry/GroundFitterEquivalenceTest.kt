package com.sailens.guidance.geometry

import com.sailens.guidance.evaluation.SealedCaptures
import com.sailens.guidance.sim.Box
import com.sailens.guidance.sim.Pose
import com.sailens.guidance.sim.SceneClasses
import com.sailens.guidance.sim.Scenarios
import com.sailens.guidance.sim.SyntheticCamera
import com.sailens.guidance.sim.World
import com.sailens.guidance.sim.renderDisparity
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.orThrow
import com.sailens.guidance.trace.capture.toTransform
import com.sailens.guidance.trace.regression.toGeometry
import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.ResizePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * [GroundFitter] against [GroundFitterBaseline], the fit as written before its inner loops were rewritten: every fit of
 * every sequence gives the same result, bit for bit -- validity, the Float fields' raw bits, every mask, gravity
 * agreement -- and so leaves the same history, which the later fits of each sequence check. The cases are the ones
 * the rewrite could get wrong: equal q (no roll makes whole rows equal), lines that tie, a tail bin, fractional
 * padding, a sample count that shrinks and grows on one fitter, outputs with NaN and infinity, and copies that go on
 * from one history separately.
 */
class GroundFitterEquivalenceTest {

    private val camera = SyntheticCamera()
    private val aw = 720
    private val ah = 1280
    private val gridW = camera.mapWidth
    private val gridH = camera.mapHeight
    private val scale = aw.toFloat() / camera.mapWidth
    private val intrinsics = CameraIntrinsics(camera.focal * scale, camera.focal * scale, camera.cx * scale, camera.cy * scale, aw, ah, IntrinsicsSource.CALIBRATION)

    private val street = Scenarios.straightClearPath.frames[0].world
    private val curb = Scenarios.curbAndDrop.frames[0].world
    private val drop = Scenarios.curbAndDrop.frames[40].world
    private val person = Scenarios.partiallyOccludedPerson.frames[0].world
    private val indoor = Scenarios.groundNoGround.frames[25].world
    private val wall = World(ground = street.ground, boxes = listOf(Box(SceneClasses.WALL, -30.0, 30.0, 1.0, 1.2, 10.0)))

    /** One fit's inputs. */
    private class Input(val map: DisparityMap, val gravity: Vec3, val time: Long?)

    private fun gravity(pose: Pose, rollDeg: Double = 0.0): Vec3 {
        val t = Math.toRadians(pose.pitchDownDeg.toDouble())
        val r = Math.toRadians(rollDeg)
        return Vec3((sin(r) * cos(t)).toFloat(), (cos(r) * cos(t)).toFloat(), sin(t).toFloat())
    }

    private fun input(
        world: World,
        pose: Pose = Pose(1.3f, 25f),
        rollDeg: Double = 0.0,
        policy: ResizePolicy = ResizePolicy.PAD_KEEP_ASPECT,
        mw: Int = 224,
        mh: Int = 392,
        noise: Double = 0.0,
        seed: Int = 7,
        a: Double = 10.0,
        b: Double = 0.2,
        asDepth: Boolean = false,
        time: Long? = null,
        poison: Boolean = false,
    ): Input {
        val transform = DepthInputTransform.create(aw, ah, mw, mh, policy)
        var values = renderDisparity(camera, pose, world, mw, mh, aw, ah, { x, y -> transform.toAnalysisX(x) to transform.toAnalysisY(y) }, a = a, b = b, noise = noise, seed = seed)
        if (asDepth) values = FloatArray(values.size) { i -> if (values[i] > 0.2001f) (10f / (values[i] - 0.2f)).coerceAtMost(200f) else 200f }
        if (poison) {
            // NaN and both infinities scattered over the image, the near ground included.
            for (i in values.indices step 37) values[i] = Float.NaN
            for (i in 5 until values.size step 53) values[i] = Float.POSITIVE_INFINITY
            for (i in 11 until values.size step 97) values[i] = Float.NEGATIVE_INFINITY
        }
        return Input(DisparityMap(mw, mh, values, transform), gravity(pose, rollDeg), time)
    }

    private fun assertSameFit(where: String, expected: GroundObservation, actual: GroundObservation) {
        assertEquals("$where: validity", expected.validity, actual.validity)
        assertEquals("$where: time", expected.frameTimeNanos, actual.frameTimeNanos)
        assertEquals("$where: scale bits", expected.disparityScale.toRawBits(), actual.disparityScale.toRawBits())
        assertEquals("$where: shift bits", expected.disparityShift.toRawBits(), actual.disparityShift.toRawBits())
        assertEquals("$where: inlier ratio bits", expected.inlierRatio.toRawBits(), actual.inlierRatio.toRawBits())
        assertEquals("$where: confidence bits", expected.confidence.toRawBits(), actual.confidence.toRawBits())
        assertEquals("$where: gravity agreement", expected.planeAgreesWithGravity, actual.planeAgreesWithGravity)
        assertEquals("$where: ground", expected.groundMask, actual.groundMask)
        assertEquals("$where: above", expected.aboveGroundMask, actual.aboveGroundMask)
        assertEquals("$where: below", expected.belowGroundMask, actual.belowGroundMask)
    }

    /** Fits [inputs] in order on a fresh pair; returns how many were valid. */
    private fun sameSequence(name: String, inputs: List<Input>, baseline: GroundFitterBaseline = GroundFitterBaseline(), fitter: GroundFitter = GroundFitter()): Int {
        var valid = 0
        for ((index, input) in inputs.withIndex()) {
            val expected = baseline.fit(input.map, intrinsics, input.gravity, gridW, gridH, input.time)
            val actual = fitter.fit(input.map, intrinsics, input.gravity, gridW, gridH, input.time)
            assertSameFit("$name, fit $index", expected, actual)
            if (actual.isValid) valid++
        }
        return valid
    }

    @Test
    fun `single scenes, each on a fresh fitter, and all of them in one history`() {
        val scenes = listOf(
            "street" to input(street),
            "street, rolled 7 degrees (no two samples share a q)" to input(street, rollDeg = 7.0),
            "street, rolled 15 degrees (gravity disagrees)" to input(street, rollDeg = 15.0),
            "curb" to input(curb),
            "drop" to input(drop),
            "wall and person" to input(person),
            "indoor floor" to input(indoor),
            "street, 3 % noise" to input(street, noise = 0.03),
            "drop, 3 % noise, another seed" to input(drop, noise = 0.03, seed = 11),
            "an inverted model" to input(street, asDepth = true),
            "a wall filling the view" to input(wall, pose = Pose(1.3f, 0f), noise = 0.02),
            "pointing straight down" to input(street, pose = Pose(1.3f, 89f)),
            "stretch" to input(street, policy = ResizePolicy.STRETCH, mw = 392, mh = 518),
            "square crop" to input(street, policy = ResizePolicy.CROP_KEEP_ASPECT, mw = 392, mh = 392),
            "square pad" to input(street, policy = ResizePolicy.PAD_KEEP_ASPECT, mw = 518, mh = 518),
            "large shift" to input(curb, a = 50.0, b = 5.0),
            "negative shift" to input(drop, a = 10.0, b = -0.15),
            "NaN and infinities in the output" to input(street, poison = true),
            "NaN and infinities over a drop" to input(drop, noise = 0.01, poison = true),
        )
        val valid = scenes.sumOf { (name, input) -> sameSequence(name, listOf(input)) }
        assertTrue("the scenes exercise valid fits, $valid", valid >= 10)
        sameSequence("all scenes in one history", scenes.map { it.second })
    }

    /**
     * Noise near the inlier band makes candidates' runs end in different bins, with a part of the failing bin still
     * explained: the run's count must leave that part out, as `runInliers` did. Smooth scenes never tell.
     */
    @Test
    fun `noisy ground, where runs end part way through a bin`() {
        val inputs = buildList {
            for (seed in 1..12) {
                add(input(street, noise = 0.04 + 0.005 * (seed % 5), seed = seed))
                add(input(curb, noise = 0.05, seed = 100 + seed, rollDeg = (seed % 4).toDouble()))
                add(input(person, noise = 0.045, seed = 200 + seed))
            }
        }
        inputs.forEachIndexed { i, input -> sameSequence("noisy scene $i on its own", listOf(input)) }
        sameSequence("noisy scenes in one history", inputs)
    }

    @Test
    fun `the 224 x 392 letterbox pads 1·75 pixels a side, as the phone's does`() {
        val transform = DepthInputTransform.create(aw, ah, 224, 392, ResizePolicy.PAD_KEEP_ASPECT)
        assertEquals(1.75f, transform.padLeft, 1e-6f)
        sameSequence("fractional padding", listOf(input(street), input(curb, noise = 0.02), input(drop), input(street, rollDeg = 3.0)))
    }

    @Test
    fun `walking up to a drop, standing at its edge, stepping back, with frame times`() {
        val inputs = buildList {
            for (i in 0 until 50) add(input(drop, pose = Pose(1.3f, 25f, walkedM = i * 0.08f), noise = 0.01, seed = i, time = i * 333_000_000L))
            val atEdge = Pose(1.3f, 25f, walkedM = 3.6f)
            for (k in 0 until 20) add(input(drop, pose = atEdge, time = (50 + k) * 333_000_000L))
            add(input(drop, pose = Pose(1.3f, 25f, walkedM = 2.0f), time = 70 * 333_000_000L))
            for (i in 0 until 10) add(input(street, noise = 0.02, seed = 100 + i, time = (71 + i) * 333_000_000L))
        }
        val valid = sameSequence("approach, edge, back", inputs)
        assertTrue(valid in 30 until inputs.size)
    }

    @Test
    fun `a sample count that shrinks and grows on one fitter leaves nothing behind`() {
        // Pitch decides how many samples clear the horizon: from nearly level to nearly straight down and back.
        val pitches = listOf(4f, 8f, 15f, 25f, 40f, 60f, 80f, 88f, 70f, 30f, 6f, 25f, 3f, 50f)
        val inputs = pitches.mapIndexed { i, p -> input(if (i % 3 == 0) curb else street, pose = Pose(1.3f, p), noise = 0.01, seed = i) }
        sameSequence("changing pitch", inputs)
    }

    @Test
    fun `copies go on from one history each on their own, and leave the original alone`() {
        val history = (0 until 37).map { i -> input(drop, pose = Pose(1.3f, 25f, walkedM = i * 0.08f), noise = 0.01, seed = i) }
        val atEdge = input(drop, pose = Pose(1.3f, 25f, walkedM = 3.6f))
        val elsewhere = input(street, noise = 0.03, seed = 3)
        val fitter = GroundFitter()
        val first = GroundFitterBaseline()
        val second = GroundFitterBaseline()
        for (h in history) {
            fitter.fit(h.map, intrinsics, h.gravity, gridW, gridH)
            first.fit(h.map, intrinsics, h.gravity, gridW, gridH)
            second.fit(h.map, intrinsics, h.gravity, gridW, gridH)
        }
        val copy = fitter.copy()
        // The original sees the edge, the copy the street; then both go on with the other one, then the same again.
        for ((round, pair) in listOf(atEdge to elsewhere, elsewhere to atEdge, atEdge to atEdge, elsewhere to elsewhere).withIndex()) {
            val (forOriginal, forCopy) = pair
            assertSameFit("original, round $round", first.fit(forOriginal.map, intrinsics, forOriginal.gravity, gridW, gridH), fitter.fit(forOriginal.map, intrinsics, forOriginal.gravity, gridW, gridH))
            assertSameFit("copy, round $round", second.fit(forCopy.map, intrinsics, forCopy.gravity, gridW, gridH), copy.fit(forCopy.map, intrinsics, forCopy.gravity, gridW, gridH))
        }
    }

    @Test
    fun `reset clears the same history`() {
        val baseline = GroundFitterBaseline()
        val fitter = GroundFitter()
        val approach = (0 until 40).map { i -> input(drop, pose = Pose(1.3f, 25f, walkedM = i * 0.09f)) }
        sameSequence("before reset", approach, baseline, fitter)
        baseline.reset()
        fitter.reset()
        sameSequence("after reset", approach.reversed(), baseline, fitter)
    }

    /**
     * The same, on the depth evidence of real captures: every depth session's outputs in its fitter's call order, from
     * its first fit, on both. Skipped unless a path is given (`-PreplayDepth=<capture or folder>`); captures never enter
     * the repository or CI.
     */
    @Test
    fun `recorded depth outputs fit the same on both`() {
        val path = System.getProperty("sailens.replay.depth").orEmpty()
        assumeTrue("set -PreplayDepth=<capture dir> to compare on recorded outputs", path.isNotBlank())
        val found = SealedCaptures.forMetrics(File(path))
        var fits = 0
        for (directory in found.open) {
            val session = CaptureSessionReader.read(directory).orThrow()
            for ((generation, records) in session.depthEvidence.groupBy { it.generation }) {
                val baseline = GroundFitterBaseline()
                val fitter = GroundFitter()
                for (record in records.filter { it.fitOrdinal != null }.sortedBy { it.fitOrdinal }) {
                    val geometry = record.geometry.toGeometry()
                    val map = DisparityMap(record.output.width, record.output.height, CaptureSessionReader.depthOutput(session, record), record.transform.toTransform())
                    val where = "${session.manifest.sessionId}/$generation run ${record.runId}"
                    val k = checkNotNull(geometry.intrinsics)
                    val g = checkNotNull(geometry.gravityInCamera)
                    assertSameFit(
                        where,
                        baseline.fit(map, k, g, record.gridWidth, record.gridHeight, geometry.frameTimeNanos),
                        fitter.fit(map, k, g, record.gridWidth, record.gridHeight, geometry.frameTimeNanos),
                    )
                    fits++
                }
            }
        }
        println("recorded depth outputs: $fits fits the same on both")
        assertTrue("no depth evidence under $path", fits > 0)
    }
}
