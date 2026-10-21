package com.sailens.guidance.sim

import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.depth.GroundContactDistanceConfig
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.Placement
import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.guidance.model.scene.SceneResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every M2a scenario with the ground-contact distance (M3a) switched on and a chest preset of
 * 1.3 m: its own goldens (`<name>.m3a.golden.txt`, with each obstacle's distance evidence), so the
 * diff against the plain golden is the behaviour M3a would change, and the invariants of §8 checked
 * on every frame. The plain goldens stay byte for byte as they were: off, M3a changes nothing.
 */
class GroundContactScenarioTest {

    private val on = PerceptionConfig.forProfile(
        profile = PerceptionProfile.DEFAULT,
        realtimeObstacleProviderType = ObstacleProviderType.DETECTION_MODEL,
    ).copy(groundContact = GroundContactDistanceConfig(enabled = true))
    private val preset = PhoneGeometrySettings(1.3f, HeightSource.PRESET, Placement.HANDHELD_CHEST)

    private fun runOn(scenario: Scenario) = GuidanceSimulator(perceptionConfig = on, phoneGeometry = { preset }).run(scenario)

    private fun check(scenario: Scenario) {
        val results = runOn(scenario)
        assertEquals(scenario.frames.size, results.size)
        assertNeverFarther(scenario, results)
        Goldens.check(scenario, "${scenario.name}.m3a.golden.txt", results.map { GuidanceSimulator.goldenLine(it, distances = true) })
    }

    private fun assertNeverFarther(scenario: Scenario, results: List<SceneResult>) {
        for (result in results) for (obstacle in result.obstacles) {
            val e = checkNotNull(obstacle.distanceEstimate) { "${scenario.name} seq ${result.sequenceNumber}: no estimate" }
            assertTrue("${scenario.name} seq ${result.sequenceNumber}: ${e.today} became ${obstacle.distance}", obstacle.distance.value <= e.today.value)
        }
    }

    @Test fun `straight clear path`() = check(Scenarios.straightClearPath)
    @Test fun `pitch change with a fixed obstacle`() = check(Scenarios.pitchChangeFixedObstacle)
    @Test fun `phone height change`() = check(Scenarios.phoneHeightChange)
    @Test fun `ground and no ground`() = check(Scenarios.groundNoGround)
    @Test fun `curb and drop`() = check(Scenarios.curbAndDrop)
    @Test fun `partially occluded person`() = check(Scenarios.partiallyOccludedPerson)
    @Test fun `tilt sweep near a person`() = check(Scenarios.tiltSweepNearPerson)
    @Test fun `approach a parked car`() = check(Scenarios.approachParkedCar)

    @Test
    fun `every scenario has an M3a golden test`() {
        val tested = javaClass.declaredMethods.count { it.isAnnotationPresent(Test::class.java) } - 4
        assertEquals("add a test per scenario", Scenarios.all.size, tested)
    }

    @Test
    fun `off, every level is today's`() {
        for (scenario in Scenarios.all) for (result in GuidanceSimulator().run(scenario)) for (obstacle in result.obstacles) {
            assertEquals("${scenario.name} seq ${result.sequenceNumber}", obstacle.distanceEstimate!!.today, obstacle.distance)
        }
    }

    @Test
    fun `on, the ground contact raises a level somewhere, so the wiring is real`() {
        val raised = Scenarios.all.sumOf { scenario ->
            runOn(scenario).sumOf { r -> r.obstacles.count { it.distance != it.distanceEstimate!!.today } }
        }
        assertTrue("no obstacle was raised in any scenario", raised > 0)
    }

    @Test
    fun `on, the seed height alone changes nothing`() {
        for (scenario in Scenarios.all) {
            val seeded = GuidanceSimulator(perceptionConfig = on).run(scenario)
            for (result in seeded) for (obstacle in result.obstacles) {
                assertEquals("${scenario.name} seq ${result.sequenceNumber}", obstacle.distanceEstimate!!.today, obstacle.distance)
            }
        }
    }
}
