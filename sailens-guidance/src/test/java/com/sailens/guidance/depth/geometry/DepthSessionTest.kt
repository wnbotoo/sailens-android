package com.sailens.guidance.depth.geometry

import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.model.trace.DepthRunTrace
import com.sailens.guidance.service.TraceService
import com.sailens.guidance.trace.NoOpTraceService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * A depth session's accounting (docs/geometry.md §6): every attempt that began is settled exactly once, by its own
 * record or as the closing record's in-flight attempt; the closing record is the session's last; skips are counted
 * into the next record.
 */
class DepthSessionTest {

    private class Records : TraceService by NoOpTraceService {
        val all: MutableList<DepthRunTrace> = Collections.synchronizedList(mutableListOf())
        override fun recordDepthRun(record: DepthRunTrace): Boolean {
            all += record
            return true
        }
    }

    private val records = Records()

    private fun session(traceSessionId: String? = "t", maxFailures: Int = 30) =
        DepthSession(7, traceSessionId, DepthGeometryConfig(maxConsecutiveFailures = maxFailures), records, elapsedMs = { 1_000L })

    private fun DepthSession.attempt(sequence: Long = 1) = checkNotNull(begin(sequence, sequence * 1_000_000L, TimeBasis.CAMERA_REALTIME, GeometryQuality.Ok))

    private fun DepthSession.publish(attempt: DepthAttempt) =
        commit(attempt, DepthRunTrace.PUBLISHED, DepthGeometrySnapshot(generation = generation, state = DepthLaneState.RUNNING, runId = attempt.runId, periodMs = 333))

    @Test
    fun `run ids count from 1 and each finished attempt writes one record`() {
        val s = session()
        val a = s.attempt(1)
        assertTrue(s.publish(a))
        val b = s.attempt(2)
        assertTrue(s.commit(b, DepthRunTrace.YIELD_DROPPED, snapshot = null))
        s.seal()
        assertEquals(listOf(1L, 2L, 2L), records.all.map { it.runId })
        assertEquals(listOf(DepthRunTrace.PUBLISHED, DepthRunTrace.YIELD_DROPPED, DepthRunTrace.CLOSED), records.all.map { it.outcome })
        assertNull(records.all.last().cancelledRunId)
        assertTrue(records.all.all { it.sessionId == "t" && it.generation == 7L })
    }

    @Test
    fun `an attempt in flight at each stage is settled by the closing record, and its own commit is dropped`() {
        for (stage in listOf(DepthGeometryLane.STAGE_PREPARE, DepthGeometryLane.STAGE_ADMIT, DepthGeometryLane.STAGE_INFER, DepthGeometryLane.STAGE_FIT)) {
            records.all.clear()
            val s = session()
            s.publish(s.attempt(1))
            val inFlight = s.attempt(2)
            s.stage(inFlight, stage)
            s.seal()
            // The attempt returns after the stop: nothing more is written for it, or for the generation.
            assertFalse(s.publish(inFlight))
            assertFalse(s.commit(inFlight, DepthRunTrace.FAILED, snapshot = null))
            val closed = records.all.last()
            assertEquals(stage, DepthRunTrace.CLOSED, closed.outcome)
            assertEquals(stage, 2L, closed.cancelledRunId)
            assertEquals(stage, stage, closed.cancelledStage)
            assertEquals(stage, listOf(1L, 2L), records.all.map { it.runId })
            // Each run id once: its own record, or the closing record's in-flight attempt.
            val settled = records.all.filter { it.outcome != DepthRunTrace.CLOSED }.map { it.runId } + listOfNotNull(closed.cancelledRunId)
            assertEquals(stage, listOf(1L, 2L), settled)
        }
    }

    @Test
    fun `after the seal nothing begins, nothing publishes, and the state stays closed`() {
        val s = session()
        s.seal()
        assertNull(s.begin(1, 1, TimeBasis.CAMERA_REALTIME, GeometryQuality.Ok))
        s.setState(DepthLaneState.PAUSED_THERMAL, null)
        s.recordSkip(DepthGeometryLane.SKIP_THERMAL)
        assertEquals(DepthLaneState.CLOSED, s.latest().state)
        assertEquals(1, records.all.size)
        assertTrue(records.all.single().skipsSinceLast.isEmpty())
    }

    @Test
    fun `a second seal writes nothing`() {
        val s = session()
        s.seal()
        s.seal()
        assertEquals(1, records.all.count { it.outcome == DepthRunTrace.CLOSED })
    }

    @Test
    fun `skips are counted into the next record, and the last ones into the closing record`() {
        val s = session()
        s.recordSkip(DepthGeometryLane.SKIP_NO_BASELINE)
        s.recordSkip(DepthGeometryLane.SKIP_NO_BASELINE)
        s.recordSkip(DepthGeometryLane.SKIP_GEOMETRY)
        s.publish(s.attempt(1))
        s.recordSkip(DepthGeometryLane.SKIP_THERMAL)
        s.seal()
        assertEquals(mapOf(DepthGeometryLane.SKIP_NO_BASELINE to 2, DepthGeometryLane.SKIP_GEOMETRY to 1), records.all[0].skipsSinceLast)
        assertEquals(mapOf(DepthGeometryLane.SKIP_THERMAL to 1), records.all[1].skipsSinceLast)
    }

    @Test
    fun `a thrown step settles the attempt as failed, with none in flight it only counts`() {
        val s = session()
        s.attempt(1)
        s.failInFlight()
        s.failInFlight()
        s.seal()
        assertEquals(listOf(DepthRunTrace.FAILED, DepthRunTrace.CLOSED), records.all.map { it.outcome })
        assertNull(records.all.last().cancelledRunId)
        assertEquals(2, records.all.last().failuresTotal)
    }

    @Test
    fun `too many failures in a row stop the lane, a success in between resets the count`() {
        val s = session(maxFailures = 3)
        repeat(2) { s.attempt(); s.failInFlight() }
        s.publish(s.attempt())
        repeat(2) { s.attempt(); s.failInFlight() }
        assertFalse(s.stopped)
        s.attempt()
        s.failInFlight()
        assertTrue(s.stopped)
        assertEquals(DepthLaneState.RUN_FAILED, s.latest().state)
        // A later publication does not revive a stopped lane.
        s.publish(s.attempt())
        assertEquals(DepthLaneState.RUN_FAILED, s.latest().state)
        s.seal()
        assertEquals("run_failed", records.all.last().finalState)
        assertEquals(5, records.all.last().failuresTotal)
    }

    @Test
    fun `a publication returns a paused or failing state to running in the same step`() {
        val s = session()
        s.publish(s.attempt())
        for (paused in listOf(DepthLaneState.PAUSED_YIELD, DepthLaneState.PAUSED_THERMAL, DepthLaneState.PREPROCESS_UNAVAILABLE)) {
            s.setState(paused, null)
            assertEquals(paused, s.latest().state)
            s.publish(s.attempt())
            assertEquals(paused.name, DepthLaneState.RUNNING, s.latest().state)
        }
    }

    @Test
    fun `the snapshot is published with its record and read with the session's current state`() {
        val s = session()
        assertNull(s.latest().observation)
        assertNull(s.latest().runId)
        val a = s.attempt(1)
        s.publish(a)
        s.setState(DepthLaneState.PAUSED_YIELD, null)
        s.recordSkip(DepthGeometryLane.SKIP_YIELD)
        val latest = s.latest()
        assertEquals(1L, latest.runId)
        assertEquals(DepthLaneState.PAUSED_YIELD, latest.state)
        assertEquals(DepthGeometryLane.SKIP_YIELD, latest.lastSkipReason)
    }

    @Test
    fun `without a trace session the accounting runs and nothing is written`() {
        val s = session(traceSessionId = null)
        assertTrue(s.publish(s.attempt()))
        s.seal()
        assertTrue(records.all.isEmpty())
    }
}
