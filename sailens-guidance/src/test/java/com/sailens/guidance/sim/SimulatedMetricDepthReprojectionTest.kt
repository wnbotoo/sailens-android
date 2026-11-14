package com.sailens.guidance.sim

import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DepthTimes
import com.sailens.vision.depth.MetricDepthMap
import com.sailens.vision.depth.ResizePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** A held measurement contains the surfaces and unknown pixels it measured, however the later view or world changes. */
class SimulatedMetricDepthReprojectionTest {
    private fun maps(scenario: Scenario, source: SimulatedMetricDepth): List<MetricDepthMap> = runBlocking {
        val model = SimulatedDepthModel(metric = source)
        val pipeline = SimulatedPipeline(scenario, depth = model)
        val producer = pipeline.SimulatedMetricDepthProducer(model, source)
        producer.initialize()
        try {
            scenario.frames.indices.map { i -> producer.infer(producer.prepare(pipeline.cameraFrame(i))).map as MetricDepthMap }
        } finally {
            producer.release()
        }
    }

    @Test
    fun `a held measurement does not see a new wall or redraw its noise and confidence`() {
        val clear = Scenarios.groundNoGround.frames[0]
        val wall = Scenarios.groundNoGround.frames[20]
        assertEquals(clear.pose, wall.pose)
        val scenario = Scenario("held_world", "A wall appears while the same measurement is held.", listOf(clear, wall, wall))
        val source = SimulatedMetricDepth(holdFrames = 2, noise = 0.01, sparse = 0.9, hole = { x, y -> x in 20..30 && y in 70..90 })
        val (first, held, updated) = maps(scenario, source)
        assertEquals(first.times.measurementKey, held.times.measurementKey)
        assertEquals(first.times.measurementTimeNanos, held.times.measurementTimeNanos)
        assertArrayEquals(first.depthM.map(Float::toRawBits).toIntArray(), held.depthM.map(Float::toRawBits).toIntArray())
        assertArrayEquals(first.confidence!!, held.confidence!!, 0f)
        assertFalse(held.times.viewTimeNanos == first.times.viewTimeNanos)
        assertFalse(first.times.measurementKey == updated.times.measurementKey)
        assertTrue("the next measurement sees the wall", first.depthM.indices.count { abs(first.depthM[it] - updated.depthM[it]) > 0.5f } > 100)
    }

    @Test
    fun `turning or moving reprojects the old surface, leaving new view, holes and low confidence unknown`() {
        val camera = SyntheticCamera()
        val world = World(listOf(GroundPatch(SceneClasses.SIDEWALK, -100.0, 100.0, 0.0, 100.0)), backgroundHeight = 0.0)
        val poses = listOf(Pose(1.3f, 25f), Pose(1.3f, 45f), Pose(1.3f, 25f, walkedM = 0.4f))
        val scenario = Scenario("held_surface", "An old measured surface viewed from new poses.", poses.map { ScenarioFrame(it, world) })
        val source = SimulatedMetricDepth(
            width = 320, height = 240, toBuffer = SimulatedMetricDepth.centreCrop(320, 240, 1280, 720), holdFrames = 3,
            hole = { x, y -> x in 80..110 && y in 180..230 },
            confidenceOf = { _, z -> if (z in 2.3..2.8) 0f else 1f },
        )
        val maps = maps(scenario, source)
        val first = maps.first()
        for (i in 1..2) {
            val map = maps[i]
            assertEquals(first.times.measurementKey, map.times.measurementKey)
            assertEquals(first.times.measurementTimeNanos, map.times.measurementTimeNanos)
            var checked = 0
            var unseen = 0
            var unmeasured = 0
            for (y in 2 until map.height - 2 step 2) for (x in 2 until map.width - 2 step 2) {
                val u = map.transform.toAnalysisX(x + 0.5f)
                val v = map.transform.toAnalysisY(y + 0.5f)
                val truth = surfaceAt(camera, poses[i], world, map.transform.analysisWidth, u, v) ?: continue
                val point = worldPoint(camera, poses[i], map, u, v, truth.first)
                val old = project(camera, poses[0], first, point)
                val depth = map.depthAt(u, v)
                if (old == null || !first.transform.covers(old.first, old.second)) {
                    assertEquals("newly visible $i $x,$y", null, depth)
                    unseen++
                } else if (!first.isKnownAt(old.first, old.second)) {
                    assertEquals("originally unknown $i $x,$y", null, depth)
                    unmeasured++
                } else if (depth != null) {
                    assertEquals("surface $i $x,$y", truth.first, depth.toDouble(), truth.first * 0.001)
                    checked++
                }
            }
            assertTrue("view $i: checked $checked", checked > 500)
            assertTrue("view $i: not measured $unmeasured", unmeasured > 100)
            if (i == 1) assertTrue("newly visible $unseen", unseen > 100)
        }
    }

    @Test
    fun `reprojection never joins the two sides of a depth edge into an unmeasured surface`() {
        val camera = SyntheticCamera(activeWidth = 16, activeHeight = 16, focalActive = 8f, bufferWidth = 16, bufferHeight = 16, mapWidth = 16, mapHeight = 16)
        val transform = DepthInputTransform.create(16, 16, 16, 16, ResizePolicy.STRETCH)
        val measured = MetricDepthMap(16, 16, FloatArray(256) { if (it % 16 < 8) 2f else 4f }, null, 0.5f, transform, DepthTimes.ofFrame(0))
        val map = reprojectMetricDepth(camera, measured, Pose(1.3f, 0f), Pose(1.3f, 0f, walkedM = 0.2f), DepthTimes.ofFrame(1))
        var near = 0
        var far = 0
        var unknown = 0
        for (z in map.depthM) {
            when {
                z.isNaN() -> unknown++
                abs(z - 1.8f) < 1e-5f -> near++
                abs(z - 3.8f) < 1e-5f -> far++
                else -> throw AssertionError("a depth edge made an unmeasured $z m surface")
            }
        }
        assertTrue("near $near, far $far, unknown $unknown", near > 50 && far > 50 && unknown > 0)
    }

    private fun worldPoint(camera: SyntheticCamera, pose: Pose, map: MetricDepthMap, u: Float, v: Float, z: Double): Vec {
        val scale = map.transform.analysisWidth.toDouble() / camera.mapWidth
        val ray = pose.forward + pose.right * ((u - camera.cx * scale) / (camera.focal * scale)) + pose.down * ((v - camera.cy * scale) / (camera.focal * scale))
        return pose.position + ray * z
    }

    private fun project(camera: SyntheticCamera, pose: Pose, map: MetricDepthMap, point: Vec): Pair<Float, Float>? {
        val relative = point - pose.position
        val z = relative dot pose.forward
        if (z <= 0) return null
        val scale = map.transform.analysisWidth.toDouble() / camera.mapWidth
        return (camera.cx * scale + camera.focal * scale * (relative dot pose.right) / z).toFloat() to
            (camera.cy * scale + camera.focal * scale * (relative dot pose.down) / z).toFloat()
    }
}
