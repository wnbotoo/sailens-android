package com.sailens.shell.sim

import com.sailens.guidance.sim.SimulatedDepthModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The depth lane changes nothing the user gets (docs/geometry.md §6, I1): every session script, run with no lane and
 * with a lane and a depth model beside the pipeline, writes the same timeline -- speech, motor, alerts, safety states,
 * cooldown receipts -- and leaves the same status cards and the same screen. The lane runs on its own threads,
 * unsynchronised with the session's.
 */
class DepthLaneSessionTest {

    @Test
    fun `every session script is the same with or without the depth lane`() {
        var inferences = 0
        for (script in SessionScenarios.all) {
            val without = SessionSimulator.run(script)
            val with = SessionSimulator.run(script, depth = SimulatedDepthModel())
            assertEquals(script.name, without.lines, with.lines)
            assertEquals(script.name, without.delivered, with.delivered)
            assertEquals(script.name, without.ledger, with.ledger)
            assertEquals(script.name, canonical(without.cards), canonical(with.cards))
            assertEquals(script.name, canonical(without.screens), canonical(with.screens))
            assertEquals(script.name, without.observationFaults, with.observationFaults)
            assertEquals(script.name, 0, without.depthInferences)
            inferences += with.depthInferences
        }
        // The lane did run beside the sessions: the proof is not of an idle lane.
        assertTrue("depth runs: $inferences", inferences > 100)
    }

    /** Each random event id replaced by the order it first appears in: two runs differ in their ids, not in which event is which. */
    private fun canonical(samples: List<Any>): List<String> {
        val ids = HashMap<String, Int>()
        return samples.map { sample -> UUID_PATTERN.replace(sample.toString()) { match -> "id" + ids.getOrPut(match.value) { ids.size } } }
    }

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
