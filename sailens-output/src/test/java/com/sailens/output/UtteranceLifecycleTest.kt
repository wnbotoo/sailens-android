package com.sailens.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * The attempt bookkeeping behind the speech lifecycle, in the orders the
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
    fun `a callback after its session ended is never reported, least of all into the next session`() {
        lifecycle.beginSession("s1")
        submit(entry("a", logical = "event-a"))
        lifecycle.endSession("s1")
        lifecycle.beginSession("s2")
        lifecycle.engineCallback(UtteranceEventKind.DONE, "a", 1, atNanos = 99)

        val unknown = only(UtteranceEventKind.UNKNOWN_TERMINAL)
        assertEquals(UtteranceReasons.SESSION_ENDED, unknown.reason)
        assertEquals("s1", unknown.session)
        assertTrue("its session is closed: the record already said 'unknown'", events.none { it.kind == UtteranceEventKind.DONE })
        // An attempt of the new session is reported as usual.
        submit(entry("b"))
        assertEquals("s2", events.last().session)
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

    // ---- Ending a session: a reconciliation, each event counted once ------------------------------

    /** An observer that blocks on [kind] until released, recording what reaches it. */
    private class Gate(private val kind: UtteranceEventKind) : UtteranceLifecycleObserver {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun onUtteranceEvent(event: UtteranceEvent) {
            if (event.kind == kind) {
                entered.countDown()
                release.await()
            }
            seen += "${event.session}:${event.kind.wireName}"
        }
    }

    private fun thread(block: () -> Unit) = Thread(block).apply { start() }

    @Test
    fun `ending a session waits for an event already decided but still on its way, and counts it`() {
        // The engine thread settles the attempt, then is descheduled before it reaches the observer.
        val gate = Gate(UtteranceEventKind.STOPPED)
        val slow = UtteranceLifecycle { 0L }.apply { observer = gate }
        slow.beginSession("s1")
        slow.submitting(entry("a"), 1)
        slow.submitted("a")
        val callback = thread { slow.engineCallback(UtteranceEventKind.STOPPED, "a", 1, atNanos = 5, interrupted = true) }
        gate.entered.await()

        val ending = thread { slow.endSession("s1", drainTimeoutMs = 10_000).also { gate.seen += "produced:$it" } }
        Thread.sleep(200)
        assertTrue("still waiting for the stop", ending.isAlive)
        gate.release.countDown()
        ending.join(5_000)
        callback.join(5_000)

        assertEquals(listOf("s1:submitted", "s1:stopped", "produced:2"), gate.seen)
    }

    @Test
    fun `a submission the engine has taken but not yet confirmed is waited for before the session closes`() {
        // Registered, speak() returned SUCCESS, and the main thread is descheduled before submitted().
        val gate = Gate(UtteranceEventKind.DONE)
        val life = UtteranceLifecycle { 0L }.apply { observer = gate }
        life.beginSession("s1")
        life.submitting(entry("a"), 1)
        val ended = CountDownLatch(1)
        var produced = -1
        val ending = thread {
            produced = life.endSession("s1", drainTimeoutMs = 10_000)
            ended.countDown()
        }
        Thread.sleep(200)
        assertTrue("the session does not close under an unconfirmed submission", ending.isAlive)
        life.submitted("a")
        ending.join(5_000)

        assertEquals(0, ended.count)
        assertEquals("the submission, then unknown: the session ended before any outcome", listOf("s1:submitted", "s1:unknown_terminal"), gate.seen)
        assertEquals(2, produced)
    }

    @Test
    fun `an unconfirmed submission past the wait is abandoned -- counted once, never reported`() {
        val gate = Gate(UtteranceEventKind.ERROR) // never blocks here
        val life = UtteranceLifecycle { 0L }.apply { observer = gate }
        life.beginSession("s1")
        life.submitting(entry("a"), 1)

        assertEquals("one event lost: the submission's", 1, life.endSession("s1", drainTimeoutMs = 50))
        life.submitted("a")
        life.engineCallback(UtteranceEventKind.DONE, "a", 1, atNanos = 9)
        assertTrue("nothing of the abandoned attempt is reported afterwards", gate.seen.isEmpty())
    }

    @Test
    fun `an event still on its way past the wait is in the count, so a recorder that misses it loses it once`() {
        val gate = Gate(UtteranceEventKind.DONE)
        val stuck = UtteranceLifecycle { 0L }.apply { observer = gate }
        stuck.beginSession("s1")
        stuck.submitting(entry("a"), 1)
        stuck.submitted("a")
        val callback = thread { stuck.engineCallback(UtteranceEventKind.DONE, "a", 1, atNanos = 5) }
        gate.entered.await()

        assertEquals("submitted and the stuck done", 2, stuck.endSession("s1", drainTimeoutMs = 50))
        gate.release.countDown()
        callback.join(5_000)
        assertEquals("nothing is counted or waited for without an observer", 0, UtteranceLifecycle { 0L }.endSession("s1"))
    }

    @Test
    fun `ending one session neither waits for nor counts another session's events`() {
        val gate = Gate(UtteranceEventKind.DONE)
        val life = UtteranceLifecycle { 0L }.apply { observer = gate }
        life.beginSession("s1")
        life.submitting(entry("a"), 1)
        life.submitted("a")
        life.beginSession("s2")
        val callback = thread { life.engineCallback(UtteranceEventKind.DONE, "a", 1, atNanos = 5) } // s1's, stuck
        gate.entered.await()

        val started = System.nanoTime()
        assertEquals(0, life.endSession("s2", drainTimeoutMs = 10_000))
        assertTrue("did not wait for s1", (System.nanoTime() - started) / 1_000_000 < 1_000)
        gate.release.countDown()
        callback.join(5_000)
    }

    @Test
    fun `ending a session again returns the same count, and a second ending waits for the first`() {
        val gate = Gate(UtteranceEventKind.DONE)
        val life = UtteranceLifecycle { 0L }.apply { observer = gate }
        life.beginSession("s1")
        life.submitting(entry("a"), 1)
        life.submitted("a")
        val callback = thread { life.engineCallback(UtteranceEventKind.DONE, "a", 1, atNanos = 5) } // stuck in delivery
        gate.entered.await()

        val results = Collections.synchronizedList(mutableListOf<Int>())
        val first = thread { results += life.endSession("s1", drainTimeoutMs = 10_000) }
        Thread.sleep(100)
        val second = thread { results += life.endSession("s1", drainTimeoutMs = 10_000) }
        Thread.sleep(100)
        assertTrue("the second ending waits for the first", first.isAlive && second.isAlive)
        gate.release.countDown()
        first.join(5_000)
        second.join(5_000)
        callback.join(5_000)

        assertEquals(listOf(2, 2), results)
        assertEquals("and later still", 2, life.endSession("s1"))
    }

    @Test
    fun `a closed session stays closed, and an abandoned attempt stays silent, however many sessions end after`() {
        lifecycle.beginSession("old")
        submit(entry("a"))
        lifecycle.submitting(entry("unconfirmed"), 1)
        lifecycle.endSession("old", drainTimeoutMs = 10)
        repeat(100) { i ->
            lifecycle.beginSession("other-$i")
            lifecycle.endSession("other-$i")
        }
        events.clear()

        lifecycle.engineCallback(UtteranceEventKind.DONE, "a", 1, atNanos = 9)
        lifecycle.submitted("unconfirmed")
        lifecycle.engineCallback(UtteranceEventKind.DONE, "unconfirmed", 1, atNanos = 10)

        assertTrue("nothing of the old session is reported: $events", events.isEmpty())
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
