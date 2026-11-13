package com.sailens.guidance.sim

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.depth.geometry.DepthBaseline
import com.sailens.guidance.depth.geometry.DepthCells
import com.sailens.guidance.depth.geometry.DepthEvidenceRecorder
import com.sailens.guidance.depth.geometry.DepthEvidenceRun
import com.sailens.guidance.depth.geometry.DepthEvidenceSlot
import com.sailens.guidance.depth.geometry.DepthGeometryCapability
import com.sailens.guidance.depth.geometry.DepthGeometryConfig
import com.sailens.guidance.depth.geometry.DepthGeometrySnapshot
import com.sailens.guidance.depth.geometry.DepthReading
import com.sailens.guidance.depth.geometry.DepthSession
import com.sailens.guidance.depth.geometry.DepthStartContext
import com.sailens.guidance.depth.geometry.DepthUnit
import com.sailens.guidance.depth.geometry.DepthViewGeometry
import com.sailens.guidance.depth.geometry.ObservationId
import com.sailens.guidance.depth.geometry.ScaleBasis
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DepthKind
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.MeasurementKey
import com.sailens.vision.depth.ResizePolicy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a main frame reads of the depth lane (`DepthReading`): enough to place and raise every cell without the depth
 * map, the same object for every frame that reads a run, and a guard that reads no phone height.
 */
class DepthReadingTest {

    private val camera = SyntheticCamera()
    private val aw = 720
    private val ah = 1280
    private val scale = aw.toFloat() / camera.mapWidth
    private val intrinsics = CameraIntrinsics(camera.focal * scale, camera.focal * scale, camera.cx * scale, camera.cy * scale, aw, ah, IntrinsicsSource.CALIBRATION)
    private val street = Scenarios.straightClearPath.frames[0].world

    private fun gravity(pose: Pose): Vec3 {
        val t = Math.toRadians(pose.pitchDownDeg.toDouble())
        return Vec3(0f, cos(t).toFloat(), sin(t).toFloat())
    }

    private fun view(pose: Pose, gridW: Int = 90, gridH: Int = 160, phone: PhoneGeometrySettings = PhoneGeometrySettings.DEFAULT) =
        DepthViewGeometry(intrinsics, gravity(pose), gridW, gridH, phone, TimeBasis.CAMERA_REALTIME, GeometryQuality.Ok)

    /** [world] from [pose] as a model of [mw] × [mh] would output it, its fit and its cells. */
    private class Fitted(val map: DisparityMap, val observation: GroundObservation, val cells: DepthCells?)

    private fun fitted(world: World, pose: Pose, mw: Int = 126, mh: Int = 224): Fitted {
        val transform = DepthInputTransform.create(aw, ah, mw, mh, ResizePolicy.PAD_KEEP_ASPECT)
        val values = renderDisparity(camera, pose, world, mw, mh, aw, ah, { x, y -> transform.toAnalysisX(x) to transform.toAnalysisY(y) })
        val map = DisparityMap(mw, mh, values, transform)
        val observation = GroundFitter().fit(map, intrinsics, gravity(pose), 90, 160)
        return Fitted(map, observation, DepthCells.of(map, observation, view(pose)))
    }

    /** [box] scaled by [k] about the camera centre at [pose]: the same picture, every depth [k] times. */
    private fun scaledAbout(pose: Pose, box: Box, k: Double): Box {
        val h = pose.heightM.toDouble()
        val z = pose.walkedM.toDouble()
        return box.copy(
            x0 = box.x0 * k, x1 = box.x1 * k,
            z0 = z + (box.z0 - z) * k, z1 = z + (box.z1 - z) * k,
            baseY = h + (box.baseY - h) * k, height = box.height * k,
        )
    }

    @Test
    fun `the same labels at two depths are cells at two depths`() {
        val pose = Pose(1.3f, 15f)
        // Above the horizon, in front of the buildings: the labels cannot tell 2 m from 3 m.
        val near = Box(SceneClasses.WALL, -0.5, 0.5, 2.0, 2.3, height = 0.8, baseY = 1.6)
        val far = scaledAbout(pose, near, 1.5)
        val a = fitted(World(street.ground, listOf(near)), pose)
        val b = fitted(World(street.ground, listOf(far)), pose)
        assertTrue(a.observation.isValid && b.observation.isValid)
        assertEquals(a.observation.groundMask, b.observation.groundMask)
        assertEquals(a.observation.aboveGroundMask, b.observation.aboveGroundMask)
        assertEquals(a.observation.belowGroundMask, b.observation.belowGroundMask)

        val ca = checkNotNull(a.cells)
        val cb = checkNotNull(b.cells)
        assertEquals(DepthUnit.PHONE_HEIGHTS, ca.unit)
        val v = view(pose)
        // A cell well inside the box's picture: 2 m and 3 m over the 1.3 m phone height, each placed along its ray.
        var compared = 0
        for (y in 0 until 160) for (x in 0 until 90) {
            val ray = v.cellRay(x, y)
            val zNear = ca.opticalDepth(x, y)
            val zFar = cb.opticalDepth(x, y)
            // Above the horizon (q < 0), where no ground is.
            if (v.cellQ(x, y) < 0f && abs(zNear - 2.0f / 1.3f) < 0.01f && abs(ray.x) < 0.2f) {
                assertEquals("cell $x,$y", 1.5f, zFar / zNear, 0.01f)
                assertNotEquals("cell $x,$y height", ca.heightAboveGround(x, y), cb.heightAboveGround(x, y), 0.05f)
                // Its height above the ground: the camera's, plus how far up the ray climbs to reach it.
                assertEquals(1f - zNear * v.cellQ(x, y), ca.heightAboveGround(x, y), 1e-5f)
                compared++
            }
        }
        assertTrue("box cells compared: $compared", compared > 20)
        // Ground is the same ground in both.
        for (y in 120 until 160) for (x in 0 until 90) assertEquals(ca.opticalDepth(x, y), cb.opticalDepth(x, y), 1e-6f)
    }

    @Test
    fun `a cell whose depth is at or beyond infinity, or that was not seen, is unknown`() {
        val pose = Pose(1.3f, 25f)
        val base = fitted(street, pose)
        val t = base.observation.disparityShift
        // Columns of the output at or behind the fitted shift, and a NaN column: their cells have no depth.
        val values = base.map.values.copyOf()
        for (y in 0 until base.map.height) {
            values[y * base.map.width + 40] = t - 0.05f
            values[y * base.map.width + 41] = t
            values[y * base.map.width + 80] = Float.NaN
        }
        val map = DisparityMap(base.map.width, base.map.height, values, base.map.transform)
        val cells = checkNotNull(DepthCells.of(map, base.observation, view(pose)))
        val v = view(pose)
        var checked = 0
        for (y in 100 until 160) for (x in 0 until 90) {
            // A sample between columns 40 and 41 reads two values at or behind the shift; one next to column 80 reads NaN.
            val left = (map.transform.toTensorX(v.cellCenterU(x)) - 0.5f).toInt()
            if (left != 40 && left !in 79..80) continue
            assertTrue("cell $x,$y: ${cells.opticalDepth(x, y)}", cells.opticalDepth(x, y).isNaN())
            assertTrue(cells.heightAboveGround(x, y).isNaN())
            checked++
        }
        assertTrue("cells checked: $checked", checked >= 60)
        assertEquals("seen, if beyond infinity", 1f, cells.coverage(cellOf(map, 40, v), 130))
        assertTrue("not seen where it reads the NaN", cells.coverage(cellOf(map, 80, v), 130) < 1f)
        // A map that covers only the left half of the image: the right half was not seen, no depth and no coverage.
        val unseen = DisparityMap(base.map.width, base.map.height, base.map.values, base.map.transform.copy(sourceWidth = aw / 2f))
        val half = checkNotNull(DepthCells.of(unseen, base.observation, v))
        for (y in 0 until 160) {
            assertTrue(half.opticalDepth(80, y).isNaN())
            assertEquals(0f, half.coverage(80, y))
            assertEquals(1f, half.coverage(10, y))
        }
        // An invalid fit says nothing about a relative map's depths.
        assertNull(DepthCells.of(map, base.observation.copy(validity = com.sailens.guidance.geometry.GroundFitValidity.NO_GROUND), v))
    }

    /** The cell whose centre samples between output columns [column] and the next. */
    private fun cellOf(map: DisparityMap, column: Int, v: DepthViewGeometry): Int =
        (0 until v.gridWidth).first { x -> (map.transform.toTensorX(v.cellCenterU(x)) - 0.5f).toInt() == column }

    @Test
    fun `a thin post in a cell changes the cell's value only when it is at the centre, and never its coverage`() {
        val pose = Pose(1.3f, 25f)
        val v = view(pose)
        // A 1 cm post 3 m out, at full analysis resolution so that it is three pixels wide; cell 45 spans u 360-368.
        val row = 60
        val zc = 3.057
        fun post(centreU: Double): World {
            val x = (centreU - intrinsics.cx) * zc / intrinsics.fx
            return World(street.ground, listOf(Box(SceneClasses.POLE, x - 0.005, x + 0.005, 3.0, 3.01, 1.0)))
        }
        val atCentre = fitted(post(v.cellCenterU(45).toDouble()), pose, aw, ah)
        val offCentre = fitted(post(361.5), pose, aw, ah)
        val clear = fitted(street, pose, aw, ah)
        val centred = checkNotNull(atCentre.cells)
        val beside = checkNotNull(offCentre.cells)
        val none = checkNotNull(clear.cells)
        assertEquals("the post at the centre: its own depth", 3.0f / 1.3f, centred.opticalDepth(45, row), 0.05f)
        assertNotEquals(centred.opticalDepth(45, row), beside.opticalDepth(45, row), 0.5f)
        // Beside the centre the cell reads what is behind the post, as if the cell were clear.
        assertEquals(none.opticalDepth(45, row), beside.opticalDepth(45, row), 1e-4f)
        // Coverage counts what was seen, not what is free: the same with the post, wherever it is, and without it.
        assertEquals(1f, centred.coverage(45, row))
        assertEquals(1f, beside.coverage(45, row))
        assertEquals(1f, none.coverage(45, row))
    }

    @Test
    fun `a reader gets copies, never the shared arrays`() {
        val cells = checkNotNull(fitted(street, Pose(1.3f, 25f)).cells)
        val before = cells.opticalDepth(45, 150)
        cells.copyOpticalDepth().fill(-1f)
        cells.copyHeightAboveGround()!!.fill(-1f)
        cells.copyCoverage().fill(-1f)
        assertEquals(before, cells.opticalDepth(45, 150))
        assertTrue(cells.heightAboveGround(45, 150) > -1f)
        assertEquals(1f, cells.coverage(45, 150))
    }

    // ---- through the lane

    /** Snapshots the lane published, one per run, through the real lane on [scenario]'s delivered frames. */
    private fun published(scenario: Scenario, phone: PhoneGeometrySettings = PhoneGeometrySettings.DEFAULT, onRun: (GroundFitter) -> Unit = {}): List<DepthGeometrySnapshot> = runBlocking {
        val fitters = Collections.synchronizedList(mutableListOf<GroundFitter>())
        val snapshots = Collections.synchronizedList(mutableListOf<DepthGeometrySnapshot>())
        lateinit var session: DepthSession
        val recorder = object : DepthEvidenceRecorder {
            override fun copy(traceSessionId: String, generation: Long, runId: Long, frame: ImageFrame) = object : DepthEvidenceSlot {
                override fun submit(run: DepthEvidenceRun) {
                    snapshots += session.latest()
                    onRun(fitters.single())
                }

                override fun discard() = Unit
            }
        }
        val pipeline = SimulatedPipeline(scenario, depth = SimulatedDepthModel(), depthFitter = { GroundFitter().also { fitters += it } }, depthEvidence = recorder)
        val lane = checkNotNull(pipeline.depthLane)
        val frames = Channel<ImageFrame>()
        session = lane.start(DepthStartContext(frames.receiveAsFlow(), {}, { BaselineQualification.Qualified }, { facts -> scenario.geometryOf(facts, phone) }, "reading"))
        try {
            withTimeout(600_000) {
                scenario.frames.forEachIndexed { index, frame -> if (frame.delivery != FrameDelivery.Lost) frames.send(pipeline.cameraFrame(index)) }
                frames.close()
                checkNotNull(session.job).join()
            }
        } finally {
            lane.stop(session)
        }
        synchronized(snapshots) { snapshots.toList() }
    }

    @Test
    fun `main frames reading one run share one observation and its cells, and each run is its own measurement`() {
        val snapshots = published(Scenarios.straightClearPath)
        val first = snapshots[0]
        val config = DepthGeometryConfig()
        // Three main frames 100 ms apart read the first run.
        val readings = (0 until 3).map { i ->
            val main = Scenarios.straightClearPath.geometryOf(FrameFacts.of(SimulatedPipeline(Scenarios.straightClearPath).cameraFrame(i)))
            DepthReading.of(first, DepthGeometryCapability.status(first, main, config.maxAgePeriods), main, config.maxAgePeriods)
        }
        for (r in readings) {
            assertEquals(ObservationId(first.generation, 1), r.observationId)
            assertEquals(MeasurementKey(first.generation, 1), r.times!!.measurementKey)
            assertSame(first.observation, r.observation)
            assertSame(first.cells, r.cells)
            assertSame(first.view, r.view)
            assertEquals(DepthKind.RELATIVE_DISPARITY, r.kind)
            assertEquals(ScaleBasis.ASSUMED_PHONE_HEIGHT, r.scaleBasis)
            // A model measures and views at its frame's time.
            assertEquals(first.frameTimeNanos, r.times!!.measurementTimeNanos)
            assertEquals(first.frameTimeNanos, r.times!!.viewTimeNanos)
        }
        assertEquals(listOf(0L, 100_000_000L, 200_000_000L), readings.map { it.times!!.ageNanos })
        assertEquals(config.maxAgePeriods * first.periodMs * 1_000_000L, readings[0].times!!.maxAgeNanos)
        // Every later run is a new measurement.
        assertEquals(snapshots.size, snapshots.map { it.measurementKey }.toSet().size)
        assertEquals((1L..snapshots.size).map { MeasurementKey(first.generation, it) }, snapshots.map { it.measurementKey })
        // No lane, nothing to read; a lane with no run yet, its status alone.
        assertEquals(DepthReading.NOT_CONFIGURED, DepthReading.of(null, com.sailens.guidance.safety.CapabilityStatus.NotConfigured, frame(), 2))
        val idle = DepthReading.of(first.copy(observation = null, runId = null), com.sailens.guidance.safety.CapabilityStatus.Unavailable(setOf("stale")), frame(), 2)
        assertNull(idle.observation)
        assertNull(idle.cells)
        assertNotNull(idle.status)
    }

    private fun frame() = CameraGeometry(0L, TimeBasis.CAMERA_REALTIME, intrinsics, Vec3(0f, 1f, 0f), 0L, PhoneGeometrySettings.DEFAULT, GeometryQuality.Ok)

    @Test
    fun `the guard and the labels read no phone height`() {
        val lines = listOf(0.9f, 1.15f, 1.5f).map { height ->
            val phone = PhoneGeometrySettings(height, HeightSource.PRESET)
            val guard = mutableListOf<String>()
            val snapshots = published(DepthBaselineScenarioTest.WALK_TO_DROP, phone) { fitter -> guard += DepthBaseline.guard(fitter).toString() }
            snapshots.forEach { assertEquals(height, it.view!!.phone.heightMeters) }
            snapshots.zip(guard).map { (s, g) ->
                DepthBaseline.observation(checkNotNull(s.observation)).toString() + g +
                    (s.cells?.let { it.copyOpticalDepth().contentHashCode() to it.copyHeightAboveGround().contentHashCode() } ?: "none")
            }
        }
        assertTrue("the guard held", lines[0].any { "holding, true" in it })
        assertEquals(lines[0], lines[1])
        assertEquals(lines[0], lines[2])
        assertFalse(lines[0].isEmpty())
    }
}
