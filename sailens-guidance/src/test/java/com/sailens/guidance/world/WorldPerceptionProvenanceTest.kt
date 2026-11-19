package com.sailens.guidance.world

import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.depth.ContactValidity
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.guidance.model.common.ObstacleRunKind
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.guidance.sim.Pose
import com.sailens.guidance.sim.Scenario
import com.sailens.guidance.sim.ScenarioFrame
import com.sailens.guidance.sim.Scenarios
import com.sailens.guidance.sim.SimulatedPipeline
import com.sailens.guidance.sim.World
import com.sailens.guidance.sim.geometryOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class WorldPerceptionProvenanceTest {
    @Test
    fun `real scheduling keeps the old semantic identity in a newly valid contact probe`() = runBlocking {
        val phone = PhoneGeometrySettings(1.3f, HeightSource.PRESET)
        val world = World(Scenarios.street(), listOf(Scenarios.personAt(0.0, 4.0)))
        val scenario = Scenario("probe", "semantic interval slower than detection",
            List(2) { ScenarioFrame(Pose(1.3f, 17f), world) }, alignClockToFrames = true)
        val config = PerceptionConfig.forProfile(
            profile = PerceptionProfile.DEFAULT, realtimeObstacleProviderType = ObstacleProviderType.DETECTION_MODEL,
        ).copy(
            semanticTargetFps = 1, detectionTargetFps = 0, trackerMinStableFrames = 1,
        )
        val pipeline = SimulatedPipeline(scenario, perceptionConfig = config, phoneGeometry = { phone }, withLocalWorld = true)
        pipeline.profiles.activateSelected()
        suspend fun run(index: Int) = pipeline.cameraFrame(index).let { frame ->
            pipeline.currentIndex = index
            pipeline.clock.advanceBy(100)
            pipeline.processFrame(frame, scenario.geometryOf(FrameFacts.of(frame), phone), recordWorldProvenance = true).getOrThrow()
        }
        val first = checkNotNull(run(0).frameEvidence)
        assertSame(first.current, first.semanticSource)
        val second = run(1)
        val evidence = checkNotNull(second.frameEvidence)
        assertEquals(ObstacleRunKind.DETECTION, second.obstacleRunKind)
        assertNotSame(evidence.current, evidence.semanticSource)
        assertSame(first.semanticSource, evidence.semanticSource)
        val obstacle = second.obstacles.single()
        assertSame(evidence.current, obstacle.measurement?.frameIdentity)
        val contact = checkNotNull(obstacle.distanceEstimate?.contact)
        assertEquals(ContactValidity.VALID, contact.validity)
        assertSame(first.semanticSource, contact.groundProbe?.semanticSource)
    }
}
