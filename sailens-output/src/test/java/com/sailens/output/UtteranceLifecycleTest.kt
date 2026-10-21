package com.sailens.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attempt bookkeeping behind the speech lifecycle (det-first plan D1.3), in the orders the
 * engine and the main thread can actually interleave.
 *
 * What is under test is attribution: every event names the logical utterance, the engine generation
 * and the session its attempt was submitted with -- never "the current" ones -- and contradictory
 * evidence is reported as it arrives rather than resolved here.
 */
class UtteranceLifecycleTest {

    private var now = 0L
    private val events = mutableListOf<UtteranceEvent>()
    private val lifecycle = UtteranceLifecycle { now }.apply { observer = UtteranceLifecycleObserver { events += it } }

    private fun entry(id: String, logical: String = id, priority: Int? = 3) =
        UtteranceLedger.Entry(id = id, text = "text of $id", owner = null, expiresAtMs = null, priority = priority, logicalId = logical)

    private fun submit(entry: UtteranceLedger.Entry, generation: Long = 1, previous: String? = null) {
        lifecycle.submitting(entry, generation, previous)
        lifecycle.submitted(entry.id)
    }

    private fun only(kind: UtteranceEventKind) = events.single { it.kind == kind }

    @Test
    fun `nothing is tracked or reported without an observer`() {
        val silent = UtteranceLifecycle { 0L }
        silent.beginSession("s")
        silent.submitting(entry("a"), 1)
        silent.submitted("a")
        silent.engineCallback(UtteranceEventKind.DONE, "a", 1, 0)
        silent.endSession("s")
        // Attached later, it knows nothing of what came before and reports only what follows.
        silent.observer = UtteranceLifecycleObserver { events += it }
        silent.engineCallback(UtteranceEventKind.DONE, "a", 1, 5)
        assertNull("an attempt submitted before observation began is unknown", events.single().logicalId)
    }

    @Test
    fun `a submission is reported with its logical id, generation, session and the time it was handed over`() {
        lifecycle.beginSession("s1")
        now = 100
        lifecycle.submitting(entry("a1", logical = "event-1"), generation = 4)
        now = 130
        lifecycle.submitted("a1")

        val submitted = only(UtteranceEventKind.SUBMITTED)
        assertEquals("event-1", submitted.logicalId)
        assertEquals(4L, submitted.engineGeneration)
        assertEquals("s1", submitted.session)
        assertEquals("timed when handed over, not when the engine answered", 100L, submitted.elapsedRealtimeNanos)
        assertEquals(3, submitted.priority)
    }

    @Test
    fun `a callback racing the speak call's return still finds its attempt`() {
        lifecycle.beginSession("s1")
        lifecycle.submitting(entry("a1", logical = "event-1"), generation = 1)
        // The engine thread starts speaking before speak() has returned on the main thread.
        lifecycle.engineCallback(UtteranceEventKind.STARTED, "a1", generation = 1, atNanos = 7)
        lifecycle.submitted("a1")

        val started = only(UtteranceEventKind.STARTED)
        assertEquals("event-1", started.logicalId)
        assertEquals("s1", started.session)
        assertEquals(7L, started.elapsedRealtimeNanos)
    }

    @Test
    fun `an old attempt's stop arriving after the new attempt started is the old attempt's`() {
        lifecycle.beginSession("s1")
        submit(entry("a", logical = "event-a"))
        submit(entry("b", logical = "event-b"))
        lifecycle.removed(listOf(entry("a", logical = "event-a")), UtteranceEventKind.SUPERSEDED, reason = null)
        lifecycle.engineCallback(UtteranceEventKind.STARTED, "b", 1, atNanos = 10)
        lifecycle.engineCallback(UtteranceEventKind.STOPPED, "a", 1, atNanos = 12, interrupted = true)

        val stopped = only(UtteranceEventKind.STOPPED)
        assertEquals("a", stopped.attemptId)
        assertEquals("event-a", stopped.logicalId)
        assertEquals(true, stopped.interrupted)
        assertEquals("event-a", only(UtteranceEventKind.SUPERSEDED).logicalId)
        assertEquals("event-b", only(UtteranceEventKind.STARTED).logicalId)
        // Both superseded and the later stop are kept: the evaluation decides, not the bookkeeping.
        assertEquals(listOf("submitted", "submitted", "superseded", "started", "stopped"), events.map { it.kind.wireName })
    }

    @Test
    fun `a hand-back keeps the logical id and names the attempt it replaces`() {
        lifecycle.beginSession("s1")
        submit(entry("a", logical = "event-a"))
        submit(entry("a~requeued_0", logical = "event-a"), previous = "a")

        val requeued = only(UtteranceEventKind.REQUEUED)
        assertEquals("a~requeued_0", requeued.attemptId)
        assertEquals("a", requeued.previousAttemptId)
        assertEquals("event-a", requeued.logicalId)
        assertEquals("s1", requeued.session)
    }

    @Test
    fun `a failed hand-back is reported against the attempt that would have been`() {
        lifecycle.beginSession("s1")
        submit(entry("a", logical = "event-a"))
        lifecycle.submitting(entry("a~requeued_0", logical = "event-a"), 1, previousAttemptId = "a")
        lifecycle.submitFailed("a~requeued_0")

        val failed = only(UtteranceEventKind.REQUEUE_FAILED)
        assertEquals("a", failed.previousAttemptId)
        assertEquals("event-a", failed.logicalId)
        // Both attempts now have an outcome: ending the session reports neither as unknown.
        lifecycle.endSession("s1")
        assertTrue(events.none { it.kind == UtteranceEventKind.UNKNOWN_TERMINAL })
    }

    @Test
    fun `done, then handed back, then the hand-back fails -- every fact is kept`() {
        // The engine finished A on its thread; the main thread's ledger still held A when Describe
        // withdrew, so A went back as B, and the engine refused B.
        lifecycle.beginSession("s1")
        submit(entry("a", logical = "event-a"))
        lifecycle.engineCallback(UtteranceEventKind.DONE, "a", 1, atNanos = 50)
        lifecycle.submitting(entry("b", logical = "event-a"), 1, previousAttemptId = "a")
        lifecycle.submitFailed("b")

        assertEquals(listOf("submitted", "done", "requeue_failed"), events.map { it.kind.wireName })
        assertTrue(events.all { it.logicalId == "event-a" })
    }

    @Test
    fun `a callback after its session ended still belongs to that session`() {
        lifecycle.beginSession("s1")
        submit(entry("a", logical = "event-a"))
        lifecycle.endSession("s1")
        lifecycle.beginSession("s2")
        lifecycle.engineCallback(UtteranceEventKind.DONE, "a", 1, atNanos = 99)

        val unknown = only(UtteranceEventKind.UNKNOWN_TERMINAL)
        assertEquals(UtteranceReasons.SESSION_ENDED, unknown.reason)
        assertEquals("s1", unknown.session)
        val done = only(UtteranceEventKind.DONE)
        assertEquals("never the session current when it arrives", "s1", done.session)
    }

    @Test
    fun `ending a session leaves other sessions' attempts alone`() {
        lifecycle.beginSession("s1")
        submit(entry("a"))
        lifecycle.beginSession("s2")
        submit(entry("b"))
        lifecycle.endSession("s1")

        assertEquals("a", only(UtteranceEventKind.UNKNOWN_TERMINAL).attemptId)
    }

    @Test
    fun `an engine restart leaves the old engine's open attempts unknown, and its late callbacks keep its generation`() {
        lifecycle.beginSession("s1")
        submit(entry("a", logical = "event-a"), generation = 1)
        submit(entry("done-before", logical = "event-0"), generation = 1)
        lifecycle.engineCallback(UtteranceEventKind.DONE, "done-before", 1, atNanos = 5)
        lifecycle.generationEnded(1)
        submit(entry("b", logical = "event-b"), generation = 2)
        // The old instance's listener still fires once.
        lifecycle.engineCallback(UtteranceEventKind.STOPPED, "a", generation = 1, atNanos = 20, interrupted = false)

        val unknown = only(UtteranceEventKind.UNKNOWN_TERMINAL)
        assertEquals("only the attempt without an outcome", "a", unknown.attemptId)
        assertEquals(UtteranceReasons.ENGINE_SHUT_DOWN, unknown.reason)
        assertEquals(1L, only(UtteranceEventKind.STOPPED).engineGeneration)
        assertEquals(2L, events.last { it.kind == UtteranceEventKind.SUBMITTED }.engineGeneration)
    }

    @Test
    fun `refusals before the engine carry the reason and no generation`() {
        lifecycle.beginSession("s1")
        lifecycle.refused("event-a", "event-a", UtteranceReasons.OUTRANKED, priority = 2)

        val refused = only(UtteranceEventKind.REFUSED)
        assertEquals(UtteranceReasons.OUTRANKED, refused.reason)
        assertNull(refused.engineGeneration)
        assertEquals(2, refused.priority)
        assertEquals("s1", refused.session)
    }

    @Test
    fun `a speak error is a refusal of the attempt it registered`() {
        lifecycle.submitting(entry("a", logical = "event-a"), 1)
        lifecycle.submitFailed("a")

        val refused = only(UtteranceEventKind.REFUSED)
        assertEquals(UtteranceReasons.SPEAK_ERROR, refused.reason)
        assertEquals("event-a", refused.logicalId)
    }

    @Test
    fun `cancellation says why, and settles the attempt`() {
        lifecycle.beginSession("s1")
        submit(entry("a"))
        lifecycle.removed(listOf(entry("a")), UtteranceEventKind.CANCELLED, UtteranceReasons.STOP)
        lifecycle.endSession("s1")

        assertEquals(UtteranceReasons.STOP, only(UtteranceEventKind.CANCELLED).reason)
        assertTrue(events.none { it.kind == UtteranceEventKind.UNKNOWN_TERMINAL })
    }

    @Test
    fun `an observer that throws never reaches the speech path`() {
        val throwing = UtteranceLifecycle { 0L }.apply { observer = UtteranceLifecycleObserver { error("boom") } }
        throwing.submitting(entry("a"), 1)
        throwing.submitted("a")
        throwing.engineCallback(UtteranceEventKind.DONE, "a", 1, 0)
    }

    @Test
    fun `old attempts are forgotten beyond the cap, settled ones first`() {
        submit(entry("open"))
        for (i in 0 until UtteranceLifecycle.MAX_ATTEMPTS + 10) {
            submit(entry("x$i"))
            lifecycle.engineCallback(UtteranceEventKind.DONE, "x$i", 1, 0)
        }
        events.clear()
        lifecycle.engineCallback(UtteranceEventKind.DONE, "open", 1, 0)
        assertEquals("the open attempt outlived settled ones", "open", events.single().logicalId)
    }
}
