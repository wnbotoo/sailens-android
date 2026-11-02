package com.sailens.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * When an utterance stops holding the speaker, against when it counts as heard. Real ledger, real spoken
 * callbacks; the rig's [Rig.offer] goes through the same [Announcement.refusalAt] and
 * [UtteranceSettlement.flushIn] as `SpeechManager.speakReadyAnnouncement`, and its posted queue is the main
 * looper. The real engine and `SpeechManager` around them are `SpeechManagerArbitrationTest`'s, on a device.
 *
 * Priorities: A and B equal, C higher.
 */
class UtteranceSettlementTest {

    private inner class Rig(windowMs: Long = UtteranceSettlement.DEFAULT_WINDOW_MS, capacity: Int = UtteranceSettlement.DEFAULT_CAPACITY) {
        val nanos = AtomicLong(0)
        val log = mutableListOf<String>()
        val ledger = UtteranceLedger()
        val callbacks = SpokenCallbacks(SUFFIX)

        /** Tasks posted to the main thread and not run yet. */
        val posted = ArrayDeque<() -> Unit>()
        val settlement = UtteranceSettlement(
            ledger = ledger,
            spokenCallbacks = callbacks,
            requeueSuffix = SUFFIX,
            nanoClock = nanos::get,
            onReleased = { log += "released:$it" },
            onTimedOut = { log += "timeout:$it" },
            capacity = capacity,
            windowMs = windowMs,
        )

        fun at(ms: Long) = nanos.set(ms * 1_000_000)

        fun offer(id: String, priority: Int, atMs: Long, expiresAtMs: Long = atMs + 10_000, generation: Long = 1): Boolean {
            val announcement = Announcement(id, id, id, priority, expiresAtMs)
            announcement.refusalAt(atMs, ledger)?.let {
                log += "refused:$id:$it"
                return false
            }
            callbacks.register(id) { log += "onSpoken:$id" }
            val entry = UtteranceLedger.Entry(id, id, null, expiresAtMs, priority)
            settlement.flushIn(entry, generation, atMs, { log += "$it:$id" }) { dropped ->
                dropped.forEach { log += "superseded:${it.id}" }
            }
            log += "accepted:$id"
            return true
        }

        /** The engine's callback, with the engine thread stopped before it could post. */
        fun endWithoutPost(id: String, heard: Boolean = true, generation: Long = 1): Long =
            settlement.engineEnded(id, generation, heard)

        fun end(id: String, heard: Boolean = true, generation: Long = 1) {
            endWithoutPost(id, heard, generation)
            posted.addLast { settlement.complete(id) }
        }

        fun runPosted() {
            while (posted.isNotEmpty()) posted.removeFirst()()
        }
    }

    @Test
    fun `an end settled before the frame frees the speaker, and the frame sees it already told`() {
        val r = Rig()
        r.offer("a", 8, atMs = 0)
        r.at(1398)
        r.end("a")
        r.runPosted()
        r.at(1460)
        val t = r.settlement.settleFrame()
        assertTrue(r.offer("b", 8, atMs = t))
        assertEquals(listOf("accepted:a", "onSpoken:a", "SPOKEN:a", "released:a", "accepted:b"), r.log)
    }

    /**
     * The sentence ended at 1398, inside [1108, 1408], and the engine thread stops before posting; its task
     * would run at 1461. Waiting for that task, A would hold the speaker at 1460, past the last moment any
     * model of the sentence's end allows, and refuse B.
     */
    @Test
    fun `an end the posted task has not reached is settled by the frame, however late that task is`() {
        val r = Rig()
        r.offer("a", 8, atMs = 150)
        r.at(1398)
        r.end("a")
        r.at(1460)
        val t = r.settlement.settleFrame()
        assertEquals(1460, t)
        assertTrue("A's end is <= the frame's time", r.offer("b", 8, atMs = t))
        r.at(1461)
        r.runPosted()
        assertEquals(
            "told before B could be accepted, and only once",
            listOf("accepted:a", "onSpoken:a", "SPOKEN:a", "released:a", "accepted:b"),
            r.log,
        )
    }

    @Test
    fun `an end during the frame leaves the frame's speaker alone, and the flushed sentence still counts as heard`() {
        val r = Rig()
        r.offer("a", 8, atMs = 0)
        r.at(1000)
        val t = r.settlement.settleFrame()
        r.at(1001)
        r.end("a")
        assertFalse("A held the speaker when the frame began", r.offer("b", 8, atMs = t))
        assertTrue(r.offer("c", 15, atMs = t))
        r.runPosted()
        assertEquals(
            listOf("accepted:a", "refused:b:outranked", "superseded:a", "accepted:c", "onSpoken:a", "SPOKEN:a", "released:a"),
            r.log,
        )
    }

    @Test
    fun `an end after the flush is a cut, then heard after all`() {
        val r = Rig()
        r.offer("a", 8, atMs = 0)
        r.at(1000)
        val t = r.settlement.settleFrame()
        assertFalse(r.offer("b", 8, atMs = t))
        assertTrue(r.offer("c", 15, atMs = t))
        r.at(1001)
        r.end("a")
        r.runPosted()
        assertEquals(
            listOf("accepted:a", "refused:b:outranked", "superseded:a", "CUT:a", "accepted:c", "onSpoken:a", "HEARD_AFTER_CUT:a", "released:a"),
            r.log,
        )
    }

    @Test
    fun `no end at all is a cut and nothing more`() {
        val r = Rig()
        r.offer("a", 8, atMs = 0)
        r.at(1000)
        val t = r.settlement.settleFrame()
        assertFalse(r.offer("b", 8, atMs = t))
        assertTrue(r.offer("c", 15, atMs = t))
        r.end("a", heard = false)
        r.runPosted()
        assertEquals(listOf("accepted:a", "refused:b:outranked", "superseded:a", "CUT:a", "accepted:c", "released:a"), r.log)
    }

    /** Whichever side of each step of an offer the engine's callback lands on. */
    @Test
    fun `an equal offer is accepted only after the old sentence is told, wherever the end lands`() {
        for (stage in 0..3) {
            val r = Rig()
            r.offer("a", 8, atMs = 0)
            r.at(1000)
            if (stage == 0) onAnotherThread { r.end("a") }
            val t = r.settlement.settleFrame()
            if (stage == 1) onAnotherThread { r.end("a") }
            val accepted = r.offer("b", 8, atMs = t)
            if (stage == 2) onAnotherThread { r.end("a") }
            if (stage == 3) onAnotherThread { r.end("a") }
            r.runPosted()
            assertEquals("stage $stage", stage == 0, accepted)
            val told = r.log.indexOf("onSpoken:a")
            assertTrue("stage $stage: told once", told >= 0 && r.log.count { it == "onSpoken:a" } == 1)
            assertEquals("stage $stage: one outcome", 1, r.log.count { it == "SPOKEN:a" })
            if (accepted) assertTrue("stage $stage: told first", told < r.log.indexOf("accepted:b"))
        }
    }

    @Test
    fun `an end recorded while the frame reads its time is on one side of it, never lost between`() {
        val r = Rig()
        r.offer("a", 8, atMs = 0)
        val insideEngineLock = CountDownLatch(1)
        val letEngineFinish = CountDownLatch(1)
        val paused = AtomicBoolean(false)
        val settlement = UtteranceSettlement(
            ledger = r.ledger,
            spokenCallbacks = r.callbacks,
            requeueSuffix = SUFFIX,
            nanoClock = {
                if (paused.compareAndSet(true, false)) {
                    insideEngineLock.countDown()
                    letEngineFinish.await(5, TimeUnit.SECONDS)
                }
                r.nanos.get()
            },
            onReleased = {},
        )
        r.at(1000)
        paused.set(true)
        val engine = Thread { settlement.engineEnded("a", 1, heard = true) }
        engine.start()
        assertTrue(insideEngineLock.await(5, TimeUnit.SECONDS))
        // The engine has read its time and not yet recorded the end: the frame has to wait for it.
        val frame = Thread { settlement.settleFrame() }
        frame.start()
        frame.join(100)
        assertTrue("the frame waits for the engine's lock", frame.isAlive)
        letEngineFinish.countDown()
        engine.join(5_000)
        frame.join(5_000)
        assertNull("the frame settled it", r.ledger.highestLivePriority(1000))
    }

    /**
     * For every frame at t: A has ended exactly when its end time is <= t. That is the frozen speaker model's
     * own comparison (`endAtMs <= t`), raced here against a real engine thread with a clock that never
     * repeats, so a read outside the lock would show.
     */
    @Test
    fun `a sentence holds the speaker at a frame exactly until its end time`() {
        repeat(20_000) { i ->
            val nanos = AtomicLong(0)
            val ledger = UtteranceLedger()
            val settlement = UtteranceSettlement(
                ledger = ledger,
                spokenCallbacks = SpokenCallbacks(SUFFIX),
                requeueSuffix = SUFFIX,
                nanoClock = { nanos.addAndGet(1_000_000) },
                onReleased = {},
            )
            ledger.add(UtteranceLedger.Entry("a", "a", null, Long.MAX_VALUE, 8))
            val endAt = AtomicLong(-1)
            val go = CountDownLatch(1)
            val engine = Thread {
                go.await()
                if (i % 2 == 0) Thread.yield()
                endAt.set(settlement.engineEnded("a", 1, heard = true) / 1_000_000)
            }
            engine.start()
            go.countDown()
            val t = settlement.settleFrame()
            val heldAtFrame = ledger.highestLivePriority(t) != null
            engine.join()
            assertEquals("end ${endAt.get()}, frame $t", endAt.get() > t, heldAtFrame)
        }
    }

    @Test
    fun `every offer of a frame is judged at the frame's time, across a deadline`() {
        val r = Rig()
        r.ledger.add(UtteranceLedger.Entry("a", "a", null, expiresAtMs = 3000, priority = 8))
        // The clock has moved past A's deadline by the second offer; the frame's time has not.
        r.at(3001)
        assertFalse(r.offer("b", 8, atMs = 2999))
        assertFalse(r.offer("c", 8, atMs = 2999))
        assertEquals(listOf("refused:b:outranked", "refused:c:outranked"), r.log)
    }

    @Test
    fun `an offer not expired at the frame's time starts, even if the clock has passed its deadline`() {
        val r = Rig()
        r.at(3001)
        assertTrue(r.offer("a", 8, atMs = 2999, expiresAtMs = 3000))
        assertEquals(listOf("accepted:a"), r.log)
    }

    @Test
    fun `an offer expired at the frame's time is refused as expired`() {
        val r = Rig()
        assertFalse(r.offer("a", 8, atMs = 3001, expiresAtMs = 3000))
        assertEquals(listOf("refused:a:expired"), r.log)
    }

    @Test
    fun `ended but not yet told when handed back, it says itself again and gets one outcome`() {
        val r = Rig()
        r.offer("a", 8, atMs = 0)
        r.end("a")
        // A withdrawal from another owner, as SpeechManager.withdraw does it.
        val owner = SpeechOwner("description")
        val survivors = r.ledger.withdrawDetailed(owner, 100).survivors
        val fresh = survivors.single().copy(id = "a${SUFFIX}0", requeuedFrom = "a")
        r.settlement.handingBack(fresh, 1)
        r.ledger.add(fresh)
        r.runPosted()
        assertEquals("the new attempt still holds the speaker", 8, r.ledger.highestLivePriority(100))
        r.end(fresh.id)
        r.runPosted()
        assertEquals(1, r.log.count { it == "SPOKEN:a" })
        assertEquals(1, r.log.count { it == "onSpoken:a" })
        assertNull(r.ledger.highestLivePriority(100))
    }

    @Test
    fun `a failed hand-back is a cut, unless the first attempt had already ended`() {
        for (endedFirst in listOf(false, true)) {
            val r = Rig()
            r.offer("a", 8, atMs = 0)
            if (endedFirst) r.end("a")
            val survivor = r.ledger.withdrawDetailed(SpeechOwner("description"), 100).survivors.single()
            val fresh = survivor.copy(id = "a${SUFFIX}0", requeuedFrom = "a")
            r.settlement.handingBack(fresh, 1)
            r.settlement.removed(listOf(fresh))
            r.runPosted()
            val outcome = r.log.filter { it.endsWith(":a") && !it.startsWith("accepted") && !it.startsWith("released") && !it.startsWith("onSpoken") }
            assertEquals(if (endedFirst) listOf("SPOKEN:a") else listOf("CUT:a"), outcome)
        }
    }

    @Test
    fun `the stop of an attempt already handed back is not the announcement's`() {
        val r = Rig()
        r.offer("a", 8, atMs = 0)
        val fresh = r.ledger.withdrawDetailed(SpeechOwner("description"), 100).survivors.single()
            .copy(id = "a${SUFFIX}0", requeuedFrom = "a")
        r.settlement.handingBack(fresh, 1)
        r.ledger.add(fresh)
        r.end("a", heard = false)
        r.runPosted()
        assertFalse(r.log.any { it == "CUT:a" })
        r.end(fresh.id)
        r.runPosted()
        assertEquals(1, r.log.count { it == "SPOKEN:a" })
    }

    @Test
    fun `a stop and then a done of one attempt is cut, then heard`() {
        val r = Rig()
        r.offer("a", 8, atMs = 0)
        r.end("a", heard = false)
        r.runPosted()
        r.end("a")
        r.runPosted()
        assertTrue(r.log.containsAll(listOf("CUT:a", "HEARD_AFTER_CUT:a")))
    }

    @Test
    fun `a done from another engine is no evidence for the outcome, while onSpoken stays as it was`() {
        val r = Rig()
        r.offer("a", 8, atMs = 0, generation = 1)
        r.end("a", generation = 2)
        r.runPosted()
        assertEquals(listOf("accepted:a", "onSpoken:a", "released:a"), r.log)
    }

    @Test
    fun `an engine gone takes its sentence with it, and leaves the other engines' alone`() {
        val r = Rig()
        r.offer("a", 8, atMs = 0, generation = 1)
        r.settlement.generationEnded(2)
        assertFalse(r.log.contains("CUT:a"))
        r.settlement.generationEnded(1)
        assertTrue(r.log.contains("CUT:a"))
    }

    @Test
    fun `no outcome within the window is a cut, and it says the engine owed one`() {
        val r = Rig(windowMs = 8_000)
        r.offer("a", 8, atMs = 0)
        r.at(7_999)
        r.settlement.settleFrame()
        assertFalse(r.log.contains("CUT:a"))
        r.at(8_000)
        r.settlement.settleFrame()
        assertEquals(listOf("accepted:a", "timeout:a", "CUT:a"), r.log)
    }

    @Test
    fun `a done past the window still calls onSpoken but gives no outcome`() {
        val r = Rig(windowMs = 8_000)
        r.offer("a", 8, atMs = 0)
        assertTrue(r.offer("c", 15, atMs = 1))
        r.at(8_000)
        r.settlement.settleFrame()
        r.end("a")
        r.runPosted()
        assertTrue(r.log.contains("onSpoken:a"))
        assertFalse(r.log.contains("HEARD_AFTER_CUT:a"))
    }

    @Test
    fun `a done past the window gives no outcome even with no frame in between to notice`() {
        val r = Rig(windowMs = 8_000)
        r.offer("a", 8, atMs = 0)
        r.offer("c", 15, atMs = 1)
        r.at(9_000)
        r.end("a")
        r.runPosted()
        assertTrue(r.log.contains("onSpoken:a"))
        assertFalse(r.log.contains("HEARD_AFTER_CUT:a"))
    }

    @Test
    fun `a sentence still open when its late done arrives past the window is a timeout, not spoken`() {
        val r = Rig(windowMs = 8_000)
        r.offer("a", 8, atMs = 0)
        r.at(9_000)
        r.end("a")
        r.runPosted()
        assertEquals(listOf("accepted:a", "onSpoken:a", "timeout:a", "CUT:a", "released:a"), r.log)
    }

    @Test
    fun `a done within the window counts however late its task runs`() {
        val r = Rig(windowMs = 8_000)
        r.offer("a", 8, atMs = 0)
        r.offer("c", 15, atMs = 1)
        r.at(7_999)
        r.end("a")
        r.at(9_000)
        r.runPosted()
        assertTrue(r.log.contains("HEARD_AFTER_CUT:a"))
    }

    @Test
    fun `an offer past the window does not time out a sentence whose done is on its way`() {
        val r = Rig(windowMs = 8_000)
        r.offer("a", 8, atMs = 0)
        r.at(7_999)
        r.end("a")
        r.at(8_001)
        // Not a frame: nothing settles first.
        r.offer("c", 15, atMs = 8_001)
        r.runPosted()
        assertFalse(r.log.contains("timeout:a"))
        assertTrue(r.log.contains("SPOKEN:a"))
    }

    private fun Rig.handedBack(atMs: Long): UtteranceLedger.Entry {
        val fresh = ledger.withdrawDetailed(SpeechOwner("description"), atMs).survivors.single()
            .let { it.copy(id = "${it.id}${SUFFIX}0", requeuedFrom = it.id) }
        settlement.handingBack(fresh, 1)
        ledger.add(fresh)
        return fresh
    }

    @Test
    fun `two attempts both done past the window give no outcome, neither vouching for the other`() {
        val r = Rig(windowMs = 8_000)
        r.offer("a", 8, atMs = 0)
        val fresh = r.handedBack(100)
        r.at(9_000)
        r.end("a")
        r.at(9_001)
        r.end(fresh.id)
        r.runPosted()
        assertFalse(r.log.contains("SPOKEN:a"))
        assertEquals("the timeout names the attempt that holds it", 1, r.log.count { it == "timeout:${fresh.id}" })
        assertEquals(1, r.log.count { it == "CUT:a" })
    }

    @Test
    fun `one attempt done within the window is enough, whichever task runs first`() {
        val r = Rig(windowMs = 8_000)
        r.offer("a", 8, atMs = 0)
        val fresh = r.handedBack(100)
        r.at(7_999)
        r.endWithoutPost("a")
        r.at(9_000)
        r.endWithoutPost(fresh.id)
        r.settlement.complete(fresh.id)
        r.settlement.complete("a")
        assertEquals(1, r.log.count { it == "SPOKEN:a" })
        assertFalse(r.log.any { it.startsWith("timeout:") })
    }

    /**
     * The decision clock and the frame are not the same moment. Same priority throughout; A really ends at
     * 1108. Decided at 1107 and reaching the main thread at 1109, B finds the speaker free, and C, decided at
     * 1600, finds B on it. Comparing at the decision clock alone says the opposite for all three: B refused,
     * C spoken (`SpeechSettlementAgreementTest`). The closed-loop branches have to cover this.
     */
    @Test
    fun `two milliseconds between decision and frame change which sentence is on the speaker`() {
        val r = Rig()
        r.offer("a", 8, atMs = 0)
        r.at(1108)
        r.end("a")
        r.at(1109)
        assertTrue(r.offer("b", 8, atMs = r.settlement.settleFrame()))
        r.at(1602)
        assertFalse(r.offer("c", 8, atMs = r.settlement.settleFrame()))
    }

    @Test
    fun `the oldest beyond capacity is given up on, once`() {
        val r = Rig(capacity = 2)
        for ((i, id) in listOf("a", "b", "c").withIndex()) r.offer(id, 8 + i, atMs = i.toLong())
        // Each flushed the one before, so a is a cut already and is simply forgotten.
        assertEquals(1, r.log.count { it == "CUT:a" })
        r.end("a")
        r.runPosted()
        assertFalse(r.log.contains("HEARD_AFTER_CUT:a"))
    }

    @Test
    fun `a throwing caller does not stop the speaker being released`() {
        val r = Rig()
        val entry = UtteranceLedger.Entry("a", "a", null, Long.MAX_VALUE, 8)
        r.settlement.flushIn(entry, 1, 0, { throw IllegalStateException("caller") }) {}
        r.end("a")
        r.runPosted()
        assertNull(r.ledger.highestLivePriority(0))
        assertEquals(listOf("released:a"), r.log)
    }

    @Test
    fun `an end during an offer reaches nobody until the offer has returned`() {
        val r = Rig()
        r.offer("a", 8, atMs = 0)
        r.at(1000)
        val t = r.settlement.settleFrame()
        onAnotherThread { r.end("a") }
        r.offer("c", 15, atMs = t)
        assertEquals("not delivered inside the offer", listOf("accepted:a", "superseded:a", "accepted:c"), r.log)
        r.runPosted()
        assertEquals(listOf("accepted:a", "superseded:a", "accepted:c", "onSpoken:a", "SPOKEN:a", "released:a"), r.log)
    }

    @Test
    fun `release gives every open announcement its cut, and later ends find nothing`() {
        val r = Rig()
        r.offer("a", 8, atMs = 0)
        r.end("a")
        r.settlement.clear()
        r.runPosted()
        assertEquals(listOf("accepted:a", "CUT:a"), r.log)
    }

    private fun onAnotherThread(block: () -> Unit) {
        val thread = Thread(block)
        thread.start()
        thread.join()
    }

    private companion object {
        const val SUFFIX = "~requeued_"
    }
}
