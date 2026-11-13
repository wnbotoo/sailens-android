package com.sailens.guidance.sim

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.depth.geometry.DepthGeometryCapability
import com.sailens.guidance.depth.geometry.DepthGeometryConfig
import com.sailens.guidance.depth.geometry.DepthGeometrySnapshot
import com.sailens.guidance.depth.geometry.DepthLaneState
import com.sailens.guidance.depth.geometry.DepthSession
import com.sailens.guidance.depth.geometry.DepthStartContext
import com.sailens.guidance.depth.geometry.ThermalLevel
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.GroundFitValidity
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.CapabilityStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The depth lane on simulator scenes (docs/geometry.md §6), through the real lane and ground fit with the simulator's
 * depth model: what each snapshot holds and what the capability says. No decision reads either; that the results do
 * not change is `DepthLaneInvarianceTest`'s.
 */
class DepthLaneScenarioTest {

    /** Runs the lane on [indices] of [scenario], one frame at a time, and returns the snapshot after each. */
    private fun snapshots(scenario: Scenario, indices: List<Int>, model: SimulatedDepthModel = SimulatedDepthModel()): List<Pair<DepthGeometrySnapshot, CameraGeometry>> = runBlocking {
        val pipeline = SimulatedPipeline(scenario, depth = model)
        val lane = checkNotNull(pipeline.depthLane)
        val frames = Channel<ImageFrame>(Channel.UNLIMITED)
        val released = Channel<Long>(Channel.UNLIMITED)
        val session: DepthSession = lane.start(
            DepthStartContext(
                frames = frames.receiveAsFlow(),
                releaseFrame = { released.trySend(it.sequenceNumber) },
                baseline = { BaselineQualification.Qualified },
                geometry = { facts -> scenario.geometryOf(facts) },
                traceSessionId = null,
            ),
        )
        try {
            withTimeout(60_000) { while (session.latest().state == DepthLaneState.INITIALIZING) delay(5) }
            indices.map { index ->
                val frame = pipeline.cameraFrame(index)
                val before = session.latest()
                frames.send(frame)
                withTimeout(60_000) {
                    check(released.receive() == frame.sequenceNumber)
                    // Given back at the preprocessing; the snapshot follows the fit, unless the lane skipped it.
                    while (session.latest().let { it.state == DepthLaneState.RUNNING && it.runId == before.runId && it.lastSkipReason == null }) delay(5)
                }
                session.latest() to geometry(scenario, frame)
            }
        } finally {
            lane.stop(session)
        }
    }

    private fun geometry(scenario: Scenario, frame: ImageFrame): CameraGeometry = scenario.geometryOf(FrameFacts.of(frame))

    private fun status(snapshot: DepthGeometrySnapshot, frame: CameraGeometry) =
        DepthGeometryCapability.status(snapshot, frame, DepthGeometryConfig().maxAgePeriods)

    @Test
    fun `an open street is ground, and the capability is available`() {
        val (snapshot, frame) = snapshots(Scenarios.straightClearPath, listOf(0)).single()
        val observation = checkNotNull(snapshot.observation)
        assertEquals(GroundFitValidity.VALID, observation.validity)
        assertTrue(observation.groundMask.countTrue() > observation.groundMask.width * observation.groundMask.height / 4)
        assertEquals(CapabilityStatus.Available, status(snapshot, frame))
        assertEquals(1L, snapshot.frameSequence)
        assertEquals("1x3x224x126>1x224x126", snapshot.modelShape)
    }

    @Test
    fun `the road past a curb is not ground, a drop is below it, a wall indoors above it`() {
        // The same pose on the open street and before the curb: the curb takes the road beyond it out of the ground.
        val flat = checkNotNull(snapshots(Scenarios.straightClearPath, listOf(0)).single().first.observation)
        val (curb, drop) = snapshots(Scenarios.curbAndDrop, listOf(0, 40)).map { checkNotNull(it.first.observation) }
        assertEquals(GroundFitValidity.VALID, curb.validity)
        assertTrue(curb.groundMask.countTrue() < flat.groundMask.countTrue())
        assertEquals(GroundFitValidity.VALID, drop.validity)
        assertTrue(drop.belowGroundMask.countTrue() > 0)
        val (indoor, _) = snapshots(Scenarios.groundNoGround, listOf(20)).single()
        val observation = checkNotNull(indoor.observation)
        assertEquals(GroundFitValidity.VALID, observation.validity)
        assertTrue(observation.aboveGroundMask.countTrue() > 0)
    }

    @Test
    fun `a model that outputs depth is refused frame by frame, and the capability stays available`() {
        val runs = snapshots(Scenarios.straightClearPath, listOf(0, 1, 2), SimulatedDepthModel(inverted = true))
        assertEquals(listOf(1L, 2L, 3L), runs.map { it.first.runId })
        for ((snapshot, frame) in runs) {
            assertEquals(GroundFitValidity.INVERSE_OUTPUT, snapshot.observation!!.validity)
            assertEquals(CapabilityStatus.Available, status(snapshot, frame))
        }
    }

    @Test
    fun `a model whose initialization throws leaves the lane failed, and says so`() = runBlocking {
        val pipeline = SimulatedPipeline(Scenarios.straightClearPath, depth = SimulatedDepthModel(initFails = true))
        val lane = checkNotNull(pipeline.depthLane)
        val session = lane.start(DepthStartContext(Channel<ImageFrame>().receiveAsFlow(), {}, { BaselineQualification.Qualified }, { Scenarios.straightClearPath.geometryOf(it) }, null))
        withTimeout(60_000) { while (session.latest().state == DepthLaneState.INITIALIZING) delay(5) }
        val frame = geometry(Scenarios.straightClearPath, pipeline.cameraFrame(0))
        assertEquals(CapabilityStatus.Unavailable(setOf(DepthGeometryCapability.INIT_FAILED)), status(session.latest(), frame))
        lane.stop(session)
    }

    @Test
    fun `a lane failing at its check or its initialization never reaches the Guidance flow`() {
        val without = GuidanceSimulator().runToEnd(Scenarios.straightClearPath)
        for (model in listOf(SimulatedDepthModel(checkThrows = true), SimulatedDepthModel(initFails = true))) {
            val with = GuidanceSimulator().runToEnd(Scenarios.straightClearPath, depth = model)
            assertEquals("$model", SimulationEnd.Completed, with.end)
            assertEquals("$model", without.results.map(GuidanceSimulator::goldenLine), with.results.map(GuidanceSimulator::goldenLine))
        }
    }

    @Test
    fun `severe heat pauses the lane, and says so`() {
        val (snapshot, frame) = snapshots(Scenarios.straightClearPath, listOf(0), SimulatedDepthModel(thermal = ThermalLevel.SEVERE)).single()
        assertEquals(DepthLaneState.PAUSED_THERMAL, snapshot.state)
        assertEquals(CapabilityStatus.Unavailable(setOf(DepthGeometryCapability.PAUSED_THERMAL)), status(snapshot, frame))
    }
}
