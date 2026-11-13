package com.sailens.guidance.sim

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.depth.geometry.DepthCells
import com.sailens.guidance.depth.geometry.DepthEvidenceRecorder
import com.sailens.guidance.depth.geometry.DepthEvidenceRun
import com.sailens.guidance.depth.geometry.DepthEvidenceSlot
import com.sailens.guidance.depth.geometry.DepthGeometryCapability
import com.sailens.guidance.depth.geometry.DepthGeometryConfig
import com.sailens.guidance.depth.geometry.DepthGeometryLane
import com.sailens.guidance.depth.geometry.DepthGeometrySnapshot
import com.sailens.guidance.depth.geometry.DepthLaneState
import com.sailens.guidance.depth.geometry.DepthReading
import com.sailens.guidance.depth.geometry.DepthSession
import com.sailens.guidance.depth.geometry.DepthSourceUnavailable
import com.sailens.guidance.depth.geometry.DepthStartContext
import com.sailens.guidance.depth.geometry.DepthUnit
import com.sailens.guidance.depth.geometry.DepthViewGeometry
import com.sailens.guidance.depth.geometry.ScaleBasis
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundFitValidity
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.CapabilityStatus
import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DepthKind
import com.sailens.vision.depth.DepthSourceMapping
import com.sailens.vision.depth.DepthTimes
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.MetricDepthMap
import com.sailens.vision.depth.ResizePolicy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Metric depth through the same fit as relative depth (docs/geometry.md §6): fed `1/Z`, the fit measures the phone's
 * height, labels the scene as relative depth does, keeps unknown what the source did not measure, and is held to the
 * same guard. The source's own faults -- quantization, range scale and offset, sparse confidence, mixed edge pixels --
 * do not make ground; a map that measured nothing, a view off its frame, an old measurement are said, not hidden.
 */
class MetricDepthTest {

    private val camera = SyntheticCamera()
    private val aw = 720
    private val ah = 1280
    private val scale = aw.toFloat() / camera.mapWidth
    private val intrinsics = CameraIntrinsics(camera.focal * scale, camera.focal * scale, camera.cx * scale, camera.cy * scale, aw, ah, IntrinsicsSource.CALIBRATION)
    private val street = World(Scenarios.street())

    // Upright depth images on the upright analysis image (rotation 0, buffer = analysis), every 4th pixel: the same
    // grid as a 180 × 320 model without letterbox.
    private val mw = 180
    private val mh = 320
    private val mapped = DepthSourceMapping.map(mw, mh, aw, ah, 0, floatArrayOf(4f, 0f, 0f, 0f, 4f, 0f)) as DepthSourceMapping.Result.Mapped

    private fun gravity(pose: Pose): Vec3 {
        val t = Math.toRadians(pose.pitchDownDeg.toDouble())
        return Vec3(0f, cos(t).toFloat(), sin(t).toFloat())
    }

    private fun view(pose: Pose) = DepthViewGeometry(intrinsics, gravity(pose), 90, 160, PhoneGeometrySettings.DEFAULT, TimeBasis.CAMERA_REALTIME, GeometryQuality.Ok)

    /** Z of [world] from [pose] on the metric grid, faults applied by [change]; and its confidence. */
    private fun metric(
        world: World,
        pose: Pose,
        confidence: ((Int, Float) -> Float)? = null,
        change: (FloatArray) -> FloatArray = { it },
    ): MetricDepthMap {
        val t = mapped.transform
        val classes = IntArray(mw * mh)
        val z = change(renderMetricDepth(camera, pose, world, mw, mh, aw, ah, { x, y -> t.toAnalysisX(x) to t.toAnalysisY(y) }, classes = classes))
        val c = confidence?.let { f -> FloatArray(mw * mh) { i -> f(classes[i], z[i]) } }
        return MetricDepthMap(mw, mh, z, c, 0.5f, t, DepthTimes.ofFrame(0))
    }

    private fun relative(world: World, pose: Pose): DisparityMap {
        val t = DepthInputTransform.create(aw, ah, mw, mh, ResizePolicy.PAD_KEEP_ASPECT)
        return DisparityMap(mw, mh, renderDisparity(camera, pose, world, mw, mh, aw, ah, { x, y -> t.toAnalysisX(x) to t.toAnalysisY(y) }), t)
    }

    private fun fit(map: com.sailens.vision.depth.DepthMap, pose: Pose, fitter: GroundFitter = GroundFitter()) =
        fitter.fit(map, intrinsics, gravity(pose), 90, 160)

    private fun cells(map: com.sailens.vision.depth.DepthMap, o: GroundObservation, pose: Pose) = checkNotNull(DepthCells.of(map, o, view(pose)))

    /** The scene's height (world Y) at cell (x, y)'s centre ray, and its Z; null for the sky. */
    private fun truth(world: World, pose: Pose, x: Int, y: Int): Pair<Double, Double>? =
        view(pose).let { v -> surfaceAt(camera, pose, world, aw, v.cellCenterU(x), v.cellCenterV(y)) }

    /** Cells whose centre and the points a depth pixel to each side hit the same smooth surface. */
    private fun interior(world: World, pose: Pose, x: Int, y: Int): Boolean {
        val v = view(pose)
        val centre = surfaceAt(camera, pose, world, aw, v.cellCenterU(x), v.cellCenterV(y)) ?: return false
        return listOf(-4f to 0f, 4f to 0f, 0f to -4f, 0f to 4f).all { (du, dv) ->
            val near = surfaceAt(camera, pose, world, aw, v.cellCenterU(x) + du, v.cellCenterV(y) + dv) ?: return@all false
            abs(near.first - centre.first) < 0.03 * centre.first && abs(near.second - centre.second) < 0.03
        }
    }

    // ---- 1, 2: the fit

    @Test
    fun `on a street the fit measures the phone height`() {
        for (height in listOf(1.15f, 1.3f)) {
            val pose = Pose(height, 25f)
            val o = fit(metric(street, pose), pose)
            assertEquals(GroundFitValidity.VALID, o.validity)
            assertEquals("A = 1/h", 1f / height, o.disparityScale, 0.01f / height)
            assertEquals(0f, o.disparityShift, 1e-3f)
            assertEquals(height, o.measuredPhoneHeightM!!, 0.01f * height)
            assertTrue(o.metricShiftRatio!! < 1e-3f)
        }
        // Relative depth measures nothing.
        val pose = Pose(1.3f, 25f)
        val r = fit(relative(street, pose), pose)
        assertNull(r.measuredPhoneHeightM)
        assertNull(r.metricShiftRatio)
    }

    @Test
    fun `a curb, a drop, a wall and a person label as relative depth does, with heights to 2 cm`() {
        val scenes = listOf(
            "curb" to (Scenarios.curbAndDrop.frames[0]),
            "drop" to (Scenarios.curbAndDrop.frames[40]),
            "wall" to (Scenarios.groundNoGround.frames[20]),
            "person" to (Scenarios.partiallyOccludedPerson.frames[0]),
        )
        for ((name, frame) in scenes) {
            val pose = frame.pose
            val world = frame.world
            val m = metric(world, pose)
            val om = fit(m, pose)
            val or = fit(relative(world, pose), pose)
            assertEquals(name, or.validity, om.validity)
            assertEquals(name, GroundFitValidity.VALID, om.validity)
            for ((which, a, b) in listOf(Triple("ground", or.groundMask, om.groundMask), Triple("above", or.aboveGroundMask, om.aboveGroundMask), Triple("below", or.belowGroundMask, om.belowGroundMask))) {
                assertEquals("$name $which", a, b)
            }
            val c = cells(m, om, pose)
            assertEquals(DepthUnit.METERS, c.unit)
            var checked = 0
            for (y in 0 until 160) for (x in 0 until 90) {
                val z = c.opticalDepth(x, y)
                if (z.isNaN() || z > 8f || !interior(world, pose, x, y)) continue
                val (trueZ, trueY) = checkNotNull(truth(world, pose, x, y))
                assertEquals("$name $x,$y depth", trueZ, z.toDouble(), 0.01 * trueZ)
                assertEquals("$name $x,$y height", trueY, c.heightAboveGround(x, y).toDouble(), 0.02)
                checked++
            }
            assertTrue("$name: $checked cells", checked > 500)
        }
    }

    // ---- 3, 4: unknown

    @Test
    fun `a hole is unknown, no depth, no label, nothing interpolated across its edge`() {
        val pose = Pose(1.3f, 25f)
        // A block of the depth image, rows 201-260 and columns 61-120, measured nothing. Cell centres read pixel pairs
        // starting on even pixels, so some pairs straddle the block's first column and row.
        val m = metric(street, pose) { z -> z.also { for (y in 201..260) for (x in 61..120) it[y * mw + x] = Float.NaN } }
        val o = fit(m, pose)
        assertEquals(GroundFitValidity.VALID, o.validity)
        val c = cells(m, o, pose)
        val v = view(pose)
        var inside = 0
        var edge = 0
        for (y in 0 until 160) for (x in 0 until 90) {
            val tx = (m.transform.toTensorX(v.cellCenterU(x)) - 0.5f).toInt()
            val ty = (m.transform.toTensorY(v.cellCenterV(y)) - 0.5f).toInt()
            // Any of the four pixels the centre reads in the hole: unknown.
            val touches = (tx in 60..120) && (ty in 200..260)
            if (!touches) continue
            assertTrue("$x,$y", c.opticalDepth(x, y).isNaN())
            assertFalse("$x,$y ground", o.groundMask.get(x, y))
            assertFalse("$x,$y above", o.aboveGroundMask.get(x, y))
            assertFalse("$x,$y below", o.belowGroundMask.get(x, y))
            if (tx == 60 || ty == 200) edge++ else inside++
            assertTrue(c.coverage(x, y) < 1f)
        }
        assertTrue("inside $inside, edge $edge", inside > 50 && edge > 10)
    }

    @Test
    fun `a low-confidence floor is unknown, not ground`() {
        val pose = Pose(1.3f, 25f)
        // A reflective patch on the sidewalk 3-5 m out.
        // A millimetre above the sidewalk, so that the rays meet it first.
        val world = World(Scenarios.street() + GroundPatch(SceneClasses.TERRAIN, -1.5, 1.5, 3.0, 5.0, y = 0.001))
        val m = metric(world, pose, confidence = { cls, _ -> if (cls == SceneClasses.TERRAIN) 0.1f else 1f })
        val o = fit(m, pose)
        assertEquals(GroundFitValidity.VALID, o.validity)
        val c = cells(m, o, pose)
        var patch = 0
        for (y in 0 until 160) for (x in 0 until 90) {
            val (z, _) = truth(world, pose, x, y) ?: continue
            val ray = view(pose).cellRay(x, y)
            val worldZ = pose.position.z + z * (pose.forward.z + pose.down.z * ray.y)
            if (abs(ray.x * z) > 1.2 || worldZ !in 3.3..4.7) continue
            assertTrue("$x,$y", c.opticalDepth(x, y).isNaN())
            assertFalse("$x,$y ground", o.groundMask.get(x, y))
            patch++
        }
        assertTrue("patch cells $patch", patch > 50)
        // Beyond the patch the sidewalk is still ground.
        assertTrue(o.groundMask.countTrue() > 1000)
    }

    // ---- 9: the source's faults

    @Test
    fun `quantization, a range scale, sparse confidence and mixed edges make no ground and keep heights near or unknown`() {
        val pose = Pose(1.3f, 25f)
        val frame = Scenarios.partiallyOccludedPerson.frames[0]
        val world = frame.world
        val faults: List<Pair<String, MetricDepthMap>> = listOf(
            "millimetres" to metric(world, pose) { z -> FloatArray(z.size) { i -> (Math.round(z[i] * 1000.0) / 1000.0).toFloat() } },
            "range scale 1.05" to metric(world, pose) { z -> FloatArray(z.size) { i -> z[i] * 1.05f } },
            "20 % confident" to run {
                val random = kotlin.random.Random(3)
                metric(world, pose, confidence = { _, _ -> if (random.nextDouble() < 0.2) 1f else 0f })
            },
            "mixed edges" to metric(world, pose) { z -> mixEdges(z) },
        )
        for ((name, m) in faults) {
            val o = fit(m, pose)
            val c = DepthCells.of(m, o, view(pose))
            for (y in 0 until 160) for (x in 0 until 90) {
                val known = !(c?.opticalDepth(x, y)?.isNaN() ?: true)
                if (!known) {
                    assertFalse("$name $x,$y unknown yet ground", o.groundMask.get(x, y))
                    continue
                }
                val (_, trueY) = truth(world, pose, x, y) ?: continue
                if (o.groundMask.get(x, y)) assertTrue("$name $x,$y ground at ${trueY} m", abs(trueY) < 0.1)
            }
            if (o.isValid && name != "range scale 1.05") {
                for (y in 0 until 160) for (x in 0 until 90) {
                    if (!o.aboveGroundMask.get(x, y) || !interior(world, pose, x, y)) continue
                    val h = checkNotNull(c).heightAboveGround(x, y)
                    if (h.isNaN()) continue
                    assertEquals("$name $x,$y height", truth(world, pose, x, y)!!.second, h.toDouble(), 0.05)
                }
            }
        }
        // A range scale is not caught by the shift check: the fit is valid and measures the height 5 % high.
        val scaled = fit(faults[1].second, pose)
        assertEquals(GroundFitValidity.VALID, scaled.validity)
        assertEquals(1.3f * 1.05f, scaled.measuredPhoneHeightM!!, 0.01f)
    }

    @Test
    fun `a range offset is caught by the shift check, and refused before the guard`() {
        val pose = Pose(1.3f, 25f)
        for (offset in listOf(0.05f, -0.05f)) {
            val fitter = GroundFitter()
            val before = fitter.guardState()
            val o = fit(metric(street, pose) { z -> FloatArray(z.size) { i -> z[i] + offset } }, pose, fitter)
            assertEquals("$offset", GroundFitValidity.METRIC_INCONSISTENT, o.validity)
            assertTrue(o.metricShiftRatio!! > 0.015f)
            assertNull(o.measuredPhoneHeightM)
            assertEquals(before, fitter.guardState())
            assertNull(fitter.guardLevelCells())
        }
        // Two centimetres pass: the check is a consistency signal, not a calibration.
        assertEquals(GroundFitValidity.VALID, fit(metric(street, pose) { z -> FloatArray(z.size) { i -> z[i] + 0.02f } }, pose).validity)
    }

    private fun mixEdges(z: FloatArray): FloatArray {
        val out = z.copyOf()
        for (y in 0 until mh) for (x in 0 until mw) {
            val i = y * mw + x
            for (n in listOfNotNull(if (x + 1 < mw) i + 1 else null, if (y + 1 < mh) i + mw else null)) {
                if (z[i].isFinite() && z[n].isFinite() && abs(z[i] - z[n]) > 0.3f * minOf(z[i], z[n])) out[i] = (z[i] + z[n]) / 2
            }
        }
        return out
    }

    // ---- through the lane

    /** What the lane published and its state, run by run, through the real lane on [scenario]'s delivered frames. */
    private class Lane(val published: List<DepthGeometrySnapshot>, val afterEach: List<DepthGeometrySnapshot>, val last: DepthGeometrySnapshot, val geometry: (FrameFacts) -> CameraGeometry)

    private fun lane(scenario: Scenario, source: SimulatedMetricDepth, phone: PhoneGeometrySettings = PhoneGeometrySettings.DEFAULT): Lane = runBlocking {
        val published = Collections.synchronizedList(mutableListOf<DepthGeometrySnapshot>())
        val afterEach = Collections.synchronizedList(mutableListOf<DepthGeometrySnapshot>())
        lateinit var session: DepthSession
        val recorder = object : DepthEvidenceRecorder {
            override fun copy(traceSessionId: String, generation: Long, runId: Long, frame: ImageFrame) = object : DepthEvidenceSlot {
                override fun submit(run: DepthEvidenceRun) = Unit
                override fun discard() = Unit
            }
        }
        val pipeline = SimulatedPipeline(scenario, depth = SimulatedDepthModel(metric = source), depthEvidence = recorder)
        val laneOf = checkNotNull(pipeline.depthLane)
        val frames = Channel<ImageFrame>()
        val geometry = { facts: FrameFacts -> scenario.geometryOf(facts, phone) }
        session = laneOf.start(DepthStartContext(frames.receiveAsFlow(), {}, { BaselineQualification.Qualified }, geometry, "metric"))
        try {
            withTimeout(180_000) {
                var lastRun: Long? = null
                val job = checkNotNull(session.job)
                for ((index, frame) in scenario.frames.withIndex()) {
                    if (frame.delivery == FrameDelivery.Lost) continue
                    // A session that ended before its frames (a source that refused itself) takes no more.
                    val taken = select<Boolean> {
                        frames.onSend(pipeline.cameraFrame(index)) { true }
                        job.onJoin { false }
                    }
                    if (!taken) break
                    // The next send waits for the lane to take it, so this one's run has settled once that returns;
                    // the last one is read after the stream ends.
                    if (index > 0) afterEach += session.latest()
                    session.latest().let { s -> if (s.runId != null && s.runId != lastRun) { lastRun = s.runId; published += s } }
                }
                frames.close()
                checkNotNull(session.job).join()
                afterEach += session.latest()
                session.latest().let { s -> if (s.runId != null && s.runId != lastRun) published += s }
            }
        } finally {
            laneOf.stop(session)
        }
        Lane(published.toList(), afterEach.toList(), afterEach.last(), geometry)
    }

    private fun walk(frames: Int, height: Float = 1.3f, pitch: (Int) -> Float = { 25f }) = Scenario(
        name = "metric_walk",
        description = "A walk down the street for the metric source.",
        frames = (0 until frames).map { i -> ScenarioFrame(Pose(height, pitch(i), walkedM = i * 0.12f), street) },
    )

    private fun status(lane: Lane, snapshot: DepthGeometrySnapshot, frameIndex: Int, scenario: Scenario): CapabilityStatus {
        val main = scenario.geometryOf(FrameFacts.of(scenario.facts(frameIndex).frame))
        return DepthGeometryCapability.status(snapshot, main, DepthGeometryConfig().maxAgePeriods)
    }

    @Test
    fun `through the lane, the measured height against the setting, metres, no accelerator`() {
        val scenario = walk(10, height = 1.15f)
        val run = lane(scenario, SimulatedMetricDepth(), PhoneGeometrySettings(1.3f, HeightSource.PRESET))
        assertEquals(10, run.published.size)
        for (s in run.published) {
            assertEquals(DepthKind.METRIC_DEPTH, s.kind)
            val check = checkNotNull(s.phoneHeight)
            assertEquals(1.15f, check.measuredM, 0.015f)
            assertEquals(1.3f, check.settingM)
            assertEquals(HeightSource.PRESET, check.settingSource)
            assertEquals((check.measuredM - 1.3f) / 1.3f, check.relativeDifference, 1e-6f)
            assertEquals(DepthUnit.METERS, s.cells!!.unit)
            assertTrue(s.cells!!.hasHeights)
        }
        val first = run.published.first()
        val main = run.geometry(FrameFacts.of(scenario.facts(0).frame))
        val reading = DepthReading.of(first, DepthGeometryCapability.status(first, main, 2), main, 2)
        assertEquals(ScaleBasis.MEASURED, reading.scaleBasis)
        assertEquals(first.phoneHeight, reading.phoneHeight)
        assertEquals(CapabilityStatus.Available, reading.status)
    }

    @Test
    fun `the 4 by 3 crop of a 16 by 9 camera, cells outside it are unknown, inside it on their true rays`() {
        val scenario = walk(1)
        val s = lane(scenario, SimulatedMetricDepth()).published.single()
        val cells = checkNotNull(s.cells)
        val pose = scenario.frames[0].pose
        var inside = 0
        for (y in 0 until 160) for (x in 0 until 90) {
            val v = view(pose).cellCenterV(y)
            if (v < 160f || v > 1120f) {
                assertTrue("$x,$y", cells.opticalDepth(x, y).isNaN())
                assertEquals(0f, cells.coverage(x, y))
                assertFalse(s.observation!!.groundMask.get(x, y))
                continue
            }
            if (!interior(street, pose, x, y)) continue
            val (z, _) = checkNotNull(truth(street, pose, x, y))
            if (z > 10) continue
            assertEquals("$x,$y", z, cells.opticalDepth(x, y).toDouble(), 0.01 * z)
            inside++
        }
        assertTrue("inside $inside", inside > 2000)
    }

    @Test
    fun `a mapping that is not a scale and an offset makes the source unavailable, and says why`() {
        val run = lane(walk(3), SimulatedMetricDepth(toBuffer = floatArrayOf(0f, 6f, 160f, 6f, 0f, 0f)))
        assertEquals(DepthLaneState.SOURCE_UNAVAILABLE, run.last.state)
        assertTrue(run.published.isEmpty())
        val main = walk(3).let { it.geometryOf(FrameFacts.of(it.facts(0).frame)) }
        assertEquals(CapabilityStatus.Unavailable(setOf(DepthSourceUnavailable.MAPPING)), DepthGeometryCapability.status(run.last, main, 2))
    }

    @Test
    fun `a map that is mostly unknown measured nothing, skipped, not failed, said, and the last measurement ages`() {
        val scenario = walk(45)
        // Measures for five frames, then nothing for forty: more than the thirty failures that would stop the lane.
        val run = lane(scenario, SimulatedMetricDepth(emptyFrom = 5))
        assertEquals(5, run.published.size)
        assertEquals(DepthLaneState.RUNNING, run.last.state)
        assertEquals(DepthGeometryLane.SKIP_NO_MEASUREMENT, run.last.lastSkipReason)
        val lastValid = run.published.last()
        assertEquals(lastValid.runId, run.last.runId)
        // Its time is the last valid measurement's: nothing refreshed it.
        assertEquals(lastValid.measurementTimeNanos, run.last.measurementTimeNanos)
        val status = status(run, run.last, 44, scenario)
        assertEquals(CapabilityStatus.Unavailable(setOf(DepthGeometryCapability.STALE, DepthGeometryLane.SKIP_NO_MEASUREMENT)), status)
        val main = scenario.geometryOf(FrameFacts.of(scenario.facts(44).frame))
        val reading = DepthReading.of(run.last, status, main, 2)
        assertEquals(40 * 100_000_000L, reading.times!!.ageNanos)
        // Holes in most of every map from the start: never a run, never a failure.
        val holed = lane(walk(35), SimulatedMetricDepth(hole = { x, y -> (x + y) % 10 != 0 }))
        assertTrue(holed.published.isEmpty())
        assertEquals(DepthLaneState.RUNNING, holed.last.state)
        assertEquals(CapabilityStatus.Unavailable(setOf(DepthGeometryCapability.STALE, DepthGeometryLane.SKIP_NO_MEASUREMENT)), status(holed, holed.last, 34, walk(35)))
    }

    @Test
    fun `a view off its frame is skipped, and an old or untimed measurement is stale`() {
        val scenario = walk(6)
        val off = lane(scenario, SimulatedMetricDepth(viewOffsetNanos = 400_000_000L))
        assertTrue(off.published.isEmpty())
        assertEquals(DepthGeometryLane.SKIP_TIME_MISMATCH, off.last.lastSkipReason)
        assertEquals(DepthLaneState.RUNNING, off.last.state)
        // Within a period of its frame: placed and published.
        assertEquals(6, lane(scenario, SimulatedMetricDepth(viewOffsetNanos = 20_000_000L)).published.size)
        // Measured a second before its view: published, and already stale by its measurement time.
        val old = lane(scenario, SimulatedMetricDepth(measurementLagNanos = 1_000_000_000L))
        assertEquals(6, old.published.size)
        val s = old.published[2]
        assertEquals(s.viewTimeNanos!! - 1_000_000_000L, s.measurementTimeNanos)
        assertEquals(CapabilityStatus.Unavailable(setOf(DepthGeometryCapability.STALE)), status(old, s, 2, scenario))
        // A source that cannot say when it measured has no age.
        val untimed = lane(scenario, SimulatedMetricDepth(reportsMeasurementTime = false)).published[0]
        assertNull(untimed.measurementTimeNanos)
        assertEquals(CapabilityStatus.Unavailable(setOf(DepthGeometryCapability.STALE)), status(old, untimed, 0, scenario))
    }

    // ---- 8: the guard

    @Test
    fun `walking to a drop, metric depth is held by the guard exactly as relative depth is`() {
        // A depth image of the model's own grid over the whole frame: 224 x 126 in the buffer, 126 x 224 upright.
        val source = SimulatedMetricDepth(width = 224, height = 126, toBuffer = floatArrayOf(1280f / 224, 0f, 0f, 0f, 720f / 126, 0f))
        val metric = lane(DepthBaselineScenarioTest.WALK_TO_DROP, source).published.map { it.observation!!.validity }
        val relative = DepthReadingTestLane.validities(DepthBaselineScenarioTest.WALK_TO_DROP)
        assertTrue("the walk ends held", metric.contains(GroundFitValidity.SUPPORT_SWITCH))
        assertEquals(relative, metric)
    }

    // ---- 11: reprojection

    @Test
    fun `one measurement reprojected for 2 s into a turning view, placed at the view, aged from the measurement, one key`() {
        // 25 frames, 2.5 s, one measurement; the phone pitches 6 degrees a frame, 60 degrees a second, 20 to 44 and back.
        val pitch = { i: Int -> if (i <= 4) 20f + 6f * i else if (i <= 8) 44f - 6f * (i - 4) else 20f + 6f * ((i - 8) % 5).coerceAtMost(4) }
        val scenario = walk(25, pitch = pitch)
        val run = lane(scenario, SimulatedMetricDepth(width = 224, height = 126, toBuffer = floatArrayOf(1280f / 224, 0f, 0f, 0f, 720f / 126, 0f), holdFrames = 25))
        assertEquals(25, run.published.size)
        assertEquals("one measurement", 1, run.published.map { it.measurementKey }.toSet().size)
        assertEquals("25 runs", 25, run.published.map { it.runId }.toSet().size)
        val measuredAt = run.published[0].measurementTimeNanos
        for ((i, s) in run.published.withIndex()) {
            assertEquals(measuredAt, s.measurementTimeNanos)
            // Each view's own gravity: the pose of the frame it is attached to.
            val expected = gravity(scenario.frames[i].pose)
            val g = s.view!!.gravityDown
            assertEquals("run $i", expected.y, g.y, 1e-3f)
            assertEquals("run $i", expected.z, g.z, 1e-3f)
            assertEquals("run $i fits", GroundFitValidity.VALID, s.observation!!.validity)
            assertEquals("run $i height", 1.3f, s.observation!!.measuredPhoneHeightM!!, 0.015f)
            val age = status(run, s, i, scenario)
            if (i * 100_000_000L <= 2 * 333_000_000L) assertEquals("run $i", CapabilityStatus.Available, age) else assertEquals("run $i", CapabilityStatus.Unavailable(setOf(DepthGeometryCapability.STALE)), age)
        }
        assertTrue("the view turned", run.published.map { it.view!!.gravityDown.z }.toSet().size > 4)
    }
}

/** The relative model's validities on a scenario through the lane, for comparing a metric source's with. */
internal object DepthReadingTestLane {
    fun validities(scenario: Scenario): List<GroundFitValidity> = runBlocking {
        val out = Collections.synchronizedList(mutableListOf<GroundFitValidity>())
        val recorder = object : DepthEvidenceRecorder {
            override fun copy(traceSessionId: String, generation: Long, runId: Long, frame: ImageFrame) = object : DepthEvidenceSlot {
                override fun submit(run: DepthEvidenceRun) {
                    out += checkNotNull(run.observation).validity
                }
                override fun discard() = Unit
            }
        }
        val pipeline = SimulatedPipeline(scenario, depth = SimulatedDepthModel(), depthEvidence = recorder)
        val lane = checkNotNull(pipeline.depthLane)
        val frames = Channel<ImageFrame>()
        val session = lane.start(DepthStartContext(frames.receiveAsFlow(), {}, { BaselineQualification.Qualified }, { scenario.geometryOf(it) }, "relative"))
        try {
            withTimeout(180_000) {
                scenario.frames.forEachIndexed { index, frame -> if (frame.delivery != FrameDelivery.Lost) frames.send(pipeline.cameraFrame(index)) }
                frames.close()
                checkNotNull(session.job).join()
            }
        } finally {
            lane.stop(session)
        }
        out.toList()
    }
}
