package com.sailens.guidance.sim

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.depth.GroundContactDistanceConfig
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.Placement
import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.guidance.model.scene.SceneResult
import com.sailens.guidance.safety.CapabilityStatus
import com.sailens.guidance.safety.GuidanceCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The depth lane changes nothing Guidance decides (docs/geometry.md §6, I1): every scenario, under every configuration
 * the goldens use, run with no lane and with a lane and a depth model (a sound one under every configuration; one that
 * outputs depth, and one whose initialization throws, under the default), gives
 * the same results frame by frame -- the golden line with distances, the events, the obstacles, the baseline, the
 * vision faults, the other capabilities. Only the depth capability and the attached snapshot may differ.
 *
 * The lane runs on its own threads, unsynchronised with the pipeline, so each run interleaves it differently; the
 * results must not care.
 */
class DepthLaneInvarianceTest {

    private val groundContact = PerceptionConfig.forProfile(
        profile = PerceptionProfile.DEFAULT,
        realtimeObstacleProviderType = ObstacleProviderType.DETECTION_MODEL,
    ).copy(groundContact = GroundContactDistanceConfig(enabled = true))
    private val chest = PhoneGeometrySettings(1.3f, HeightSource.PRESET, Placement.HANDHELD_CHEST)

    /** The rendering of the scenario being compared, shared by its runs (each scenario is run eight times). */
    private val renders = ScenarioRenders()

    /** The simulators the goldens are written with. */
    private val configurations: List<Pair<String, (Scenario) -> GuidanceSimulator>> = listOf(
        "default" to { scenario ->
            when (scenario) {
                EdgeScenarios.rateLimitedDetTtl -> GuidanceSimulator(perceptionConfig = EdgeScenarios.rateLimitedDetection, renders = renders)
                EdgeScenarios.corridorNarrowing -> GuidanceSimulator(AnalysisConfig(enableNarrowingEvents = true), renders = renders)
                else -> GuidanceSimulator(renders = renders)
            }
        },
        "ground contact" to { _ -> GuidanceSimulator(perceptionConfig = groundContact, phoneGeometry = { chest }, renders = renders) },
        "det first" to { _ -> GuidanceSimulator(AnalysisConfig(detFirst = DetFirstConfig.LAUNCH), groundContact, { chest }, renders) },
    )

    // Every model under the default configuration; the others, which change Guidance and not the lane, with the sound one.
    private val sound = SimulatedDepthModel()
    private val allModels = listOf(sound, SimulatedDepthModel(inverted = true), SimulatedDepthModel(initFails = true))
    private fun models(configuration: String) = if (configuration == "default") allModels else listOf(sound)

    /** Everything Guidance decides or reports for a frame, the depth lane's own fields left out. */
    private fun decided(result: SceneResult) = listOf(
        GuidanceSimulator.goldenLine(result, distances = true),
        result.events,
        result.obstacles,
        result.qualification.baseline,
        result.visionFaults,
        result.qualification.capabilities - GuidanceCapability.DEPTH_GEOMETRY,
        result.frameReceivedAtMs,
        result.cameraGeometry,
    ).toString()

    /**
     * A run's frames, with each random id (events, tracks) replaced by the order it first appears in: two runs differ
     * in their ids, not in which event or track is which.
     */
    private fun decided(results: List<SceneResult>): List<String> {
        val ids = HashMap<String, Int>()
        return results.map { result -> UUID_PATTERN.replace(decided(result)) { match -> "id" + ids.getOrPut(match.value) { ids.size } } }
    }

    @Test
    fun `every scenario decides the same with or without the depth lane`() {
        var compared = 0
        var observed = 0
        val scenarios = Scenarios.all + EdgeScenarios.all
        // Scenario first: the simulator keeps the last scenario's rendering.
        for (scenario in scenarios) {
            for ((name, simulator) in configurations) {
                val without = simulator(scenario).runToEnd(scenario)
                for (model in models(name)) {
                    val with = simulator(scenario).runToEnd(scenario, depth = model)
                    val label = "$name / ${scenario.name} / $model"
                    assertEquals(label, without.end.javaClass, with.end.javaClass)
                    assertEquals(label, (without.end as? SimulationEnd.Failed)?.error?.message, (with.end as? SimulationEnd.Failed)?.error?.message)
                    assertEquals(label, decided(without.results), decided(with.results))
                    // Without a lane the capability is not configured; with one it is whatever the lane says.
                    assertTrue(label, without.results.all { it.qualification.capabilities[GuidanceCapability.DEPTH_GEOMETRY] == CapabilityStatus.NotConfigured })
                    assertTrue(label, without.results.all { it.depthGeometry == null })
                    compared += with.results.size
                    if (model == sound) observed += with.results.count { it.depthGeometry?.observation != null }
                }
            }
        }
        assertTrue("compared $compared frames", compared > 1_000)
        // The lane did publish beside the pipeline: the proof is not of an idle lane.
        assertTrue("frames with an observation attached: $observed", observed > 200)
    }

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
