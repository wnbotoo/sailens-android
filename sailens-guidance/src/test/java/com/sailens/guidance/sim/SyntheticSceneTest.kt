package com.sailens.guidance.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The simulator's two outputs must describe the same camera: what it renders (class map, boxes)
 * and the M0a raw facts it hands M3o. Checked here by projecting world points the long way, from the
 * raw facts alone -- active-array intrinsics, sensor-to-buffer transform, rotation, gravity -- and
 * comparing with the renderer, which works from [SyntheticCamera]'s derived focal length and centre.
 */
class SyntheticSceneTest {

    private val camera = SyntheticCamera()

    @Test
    fun `the camera resolves to the SM8850 class-map intrinsics`() {
        assertEquals(449.625f, camera.focal, 1e-3f)
        assertEquals(180f, camera.cx, 1e-3f)
        assertEquals(320f, camera.cy, 1e-3f)
    }

    @Test
    fun `detection boxes agree with a projection from the raw facts`() {
        for (scenario in Scenarios.all) {
            scenario.frames.forEachIndexed { index, frame ->
                val rendered = render(scenario.camera, frame.pose, frame.world)
                val boxes = frame.world.boxes.filter { it.detection != null }
                val facts = scenario.facts(index)
                assertEquals("${scenario.name}: every detected box is in view", boxes.size, rendered.detections.size)
                rendered.detections.zip(boxes).forEach { (detection, box) ->
                    val visibleBase = maxOf(box.baseY, box.detection?.visibleAboveM ?: box.baseY)
                    val corners = listOf(box.x0, box.x1).flatMap { x ->
                        listOf(visibleBase, box.baseY + box.height).flatMap { y -> listOf(box.z0, box.z1).map { z -> Triple(x, y, z) } }
                    }.map { (x, y, z) -> projectFromFacts(facts, frame.pose.heightM.toDouble(), frame.pose.walkedM.toDouble(), x, y, z) }
                    val left = (corners.minOf { it.first } / camera.mapWidth).coerceIn(0.0, 1.0)
                    val bottom = (corners.maxOf { it.second } / camera.mapHeight).coerceIn(0.0, 1.0)
                    val where = "${scenario.name} frame ${index + 1}"
                    assertEquals("$where left", left, detection.boundingBox.x.toDouble(), 1e-4)
                    assertEquals("$where bottom", bottom, detection.boundingBox.maxY.toDouble(), 1e-4)
                }
            }
        }
    }

    @Test
    fun `the horizon renders where the raw facts put it`() {
        // Looking level, the far background meets the sky; the row of that edge follows from the
        // facts: building top at 18 m, 40 m away, camera 1.3 m up.
        val pose = Pose(1.3f, 0f)
        val world = World(ground = emptyList())
        val ids = render(camera, pose, world).classMap
        val column = camera.mapWidth / 2
        val firstBuildingRow = (0 until camera.mapHeight).first { ids[it * camera.mapWidth + column] == SceneClasses.BUILDING }
        val facts = Scenario("level", "", listOf(ScenarioFrame(pose, world))).facts(0)
        val (_, edge) = projectFromFacts(facts, 1.3, 0.0, 0.0, 18.0, 40.0)
        // The first row whose pixel centre lies below the edge.
        assertTrue("edge at row $edge, first building row $firstBuildingRow", firstBuildingRow + 0.5 - edge in 0.0..1.0)
    }

    /**
     * World point -> class-map pixel using only the raw facts. Device axes: x right, y up, z toward
     * the user (portrait); the back camera looks along -z. Gravity gives "up" in device axes.
     */
    private fun projectFromFacts(facts: ScenarioFacts, heightM: Double, walkedM: Double, x: Double, y: Double, z: Double): Pair<Double, Double> {
        val g = facts.gravity.last().values.map(Float::toDouble)
        val norm = sqrt(g.sumOf { it * it })
        // Camera axes: x right, y down, z forward = device (x, -y, -z).
        val up = doubleArrayOf(g[0] / norm, -g[1] / norm, -g[2] / norm)
        val right = doubleArrayOf(1.0, 0.0, 0.0)
        // Level forward: up x right.
        val forward = doubleArrayOf(up[1] * right[2] - up[2] * right[1], up[2] * right[0] - up[0] * right[2], up[0] * right[1] - up[1] * right[0])
        val p = DoubleArray(3) { right[it] * x + up[it] * (y - heightM) + forward[it] * (z - walkedM) }

        val k = checkNotNull(facts.camera.lensIntrinsicCalibration).map(Float::toDouble)
        // Back camera, sensor orientation 90: sensor +x is upright down, sensor +y is upright left.
        check(facts.camera.sensorOrientationDegrees == 90)
        val sensorX = k[0] * (p[1] / p[2]) + k[2]
        val sensorY = k[1] * (-p[0] / p[2]) + k[3]
        val m = checkNotNull(facts.frame.sensorToBufferTransform).map(Float::toDouble)
        val bufferX = m[0] * sensorX + m[1] * sensorY + m[2]
        val bufferY = m[3] * sensorX + m[4] * sensorY + m[5]
        // Rotate the buffer 90 degrees clockwise to upright, then scale to the class map.
        check(facts.frame.rotationDegrees == 90)
        val uprightX = facts.frame.sourceHeight - bufferY
        val uprightY = bufferX
        val scale = camera.mapWidth.toDouble() / facts.frame.sourceHeight
        return uprightX * scale to uprightY * scale
    }
}
