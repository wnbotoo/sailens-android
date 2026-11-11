package com.sailens.guidance.trace

import com.sailens.guidance.model.trace.DepthRunTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The trace session's boundary for depth records: a record is accepted only while the trace session it names is
 * attached, numbered within it and counted in its summary; one that arrives after its session closed is in no trace.
 */
class DepthRunLedgerTest {

    private fun run(session: String, runId: Long, outcome: String = DepthRunTrace.PUBLISHED) =
        DepthRunTrace(sessionId = session, generation = 1, runId = runId, outcome = outcome, atElapsedMs = runId)

    private fun metadata(id: String) = SessionTraceMetadata(sessionId = id, startedAt = 0L, pipelineMode = "default", targetHardwareProfile = "test")

    private fun summary(id: String) = SessionTraceSummary(
        sessionId = id, startedAt = 0L, completedAt = 0L, totalFrames = 0, droppedFrames = 0, totalEvents = 0,
        blockedFrames = 0, dangerousFrames = 0, avgProcessFrameMs = 0.0, avgTotalPipelineMs = 0.0, avgInferenceMs = 0.0,
        p95TotalPipelineMs = 0L, maxTotalPipelineMs = 0L,
    )

    private fun describe(entry: TraceQueueEntry): String = when (entry) {
        is TraceQueueEntry.SessionStart -> "start ${entry.metadata.sessionId}"
        is TraceQueueEntry.DepthRun -> entry.record.let { "depth ${it.sessionId}#${it.seq} run=${it.runId} ${it.outcome}" }
        is TraceQueueEntry.SessionSummary -> "summary ${entry.summary.sessionId} depth=${entry.summary.depthRunCount}"
        else -> entry.javaClass.simpleName
    }

    private fun drain(queue: CountedTraceQueue<TraceQueueEntry>) = generateSequence { queue.pollCounted() }.map(::describe).toList()

    private fun ledger(queue: CountedTraceQueue<TraceQueueEntry>) = SafetyTraceLedger(queue, elapsedClock = { 0L }, wallClock = { 0L })

    @Test
    fun `records of the attached session are numbered and counted, others are refused`() {
        val queue = CountedTraceQueue<TraceQueueEntry>()
        val ledger = ledger(queue)
        assertFalse("no session attached", ledger.recordDepthRun(run("a", 1)))
        ledger.attach(metadata("a"))
        assertTrue(ledger.recordDepthRun(run("a", 1)))
        assertFalse("another session's record", ledger.recordDepthRun(run("b", 1)))
        assertTrue(ledger.recordDepthRun(run("a", 2, DepthRunTrace.CLOSED)))
        ledger.detach(summary("a"))
        assertFalse("after the closing", ledger.recordDepthRun(run("a", 3)))
        assertEquals(
            listOf("start a", "depth a#1 run=1 published", "depth a#2 run=2 closed", "summary a depth=2"),
            drain(queue),
        )
    }

    @Test
    fun `a record of a closed trace session never lands in the next one`() {
        val queue = CountedTraceQueue<TraceQueueEntry>()
        val ledger = ledger(queue)
        ledger.attach(metadata("a"))
        ledger.detach(summary("a"))
        ledger.attach(metadata("b"))
        // The depth session of trace A was not stopped; its late record names A.
        assertFalse(ledger.recordDepthRun(run("a", 1)))
        assertTrue(ledger.recordDepthRun(run("b", 1)))
        ledger.detach(summary("b"))
        assertEquals(
            listOf("start a", "summary a depth=0", "start b", "depth b#1 run=1 published", "summary b depth=1"),
            drain(queue),
        )
    }

    @Test
    fun `a record parked inside the ledger lock is queued before the closing, which waits for it`() {
        val park = AtomicBoolean(false)
        val parked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val queue = CountedTraceQueue<TraceQueueEntry>(afterReserve = {
            if (park.compareAndSet(true, false)) {
                parked.countDown()
                check(resume.await(20, TimeUnit.SECONDS)) { "never resumed" }
            }
        })
        val ledger = ledger(queue)
        ledger.attach(metadata("a"))
        park.set(true)
        val producer = thread { ledger.recordDepthRun(run("a", 1)) }
        assertTrue("the record never reached the lock", parked.await(20, TimeUnit.SECONDS))
        val detached = CountDownLatch(1)
        val closer = thread { ledger.detach(summary("a")); detached.countDown() }
        assertFalse("the closing did not wait for the record", detached.await(200, TimeUnit.MILLISECONDS))
        resume.countDown()
        producer.join()
        closer.join()
        assertEquals(listOf("start a", "depth a#1 run=1 published", "summary a depth=1"), drain(queue))
    }
}
