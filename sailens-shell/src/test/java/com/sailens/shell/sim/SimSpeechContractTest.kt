package com.sailens.shell.sim

import com.sailens.output.Announcement
import com.sailens.output.SpeechEngineState
import com.sailens.output.SpeechManager
import com.sailens.output.UtteranceOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speech double's model of `SpeechManager` and `UtteranceSettlement` (sailens-output), on its own: what it takes,
 * what it cuts, what holds the speaker, and that every caller hears of each sentence exactly once. Time is a bare
 * queue of the double's own sentence ends.
 */
class SimSpeechContractTest {

    private class Bench {
        var now = 0L
        private val ends = mutableListOf<Triple<Long, Long, () -> Unit>>()
        private val cancelled = HashSet<Long>()
        private var order = 0L
        val lines = mutableListOf<TimelineEntry.Speech>()

        val speech = SimSpeech(
            clock = { now },
            scheduleEnd = { atMs, end ->
                val id = order++
                ends += Triple(atMs, id, end)
                ({ cancelled += id })
            },
            record = { lines += it },
            now = { now },
        )

        /** Runs every end at or before [atMs], in time order, and sets the clock there. */
        fun advanceTo(atMs: Long) {
            while (true) {
                val next = ends.filter { it.second !in cancelled }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: break
                if (next.first > atMs) break
                ends.remove(next)
                now = next.first
                next.third()
            }
            now = atMs
        }

        fun kinds(id: String) = lines.filter { it.id == id }.map { it.kind }
    }

    /** One sentence's callbacks, as the delivery protocol registers them. */
    private class Heard {
        var spoken = 0
        val outcomes = mutableListOf<UtteranceOutcome>()
    }

    private fun Bench.say(id: String, priority: Int, key: String = "k", expiresAtMs: Long = Long.MAX_VALUE, heard: Heard = Heard()): Pair<Boolean, Heard> {
        val taken = speech.speak(Announcement(id, key, key, priority, expiresAtMs), { heard.spoken++ }, { heard.outcomes += it }, now)
        return taken to heard
    }

    @Test
    fun `a sentence that ended before the stop was spoken once and is not cut`() {
        val bench = Bench()
        val (_, heard) = bench.say("a", priority = 8)
        bench.advanceTo(SimSpeech.durationMs("k") + 1)
        bench.speech.stop()
        bench.advanceTo(10_000)
        assertEquals(listOf(UtteranceOutcome.SPOKEN), heard.outcomes)
        assertEquals(1, heard.spoken)
        assertEquals(listOf("speak", "done"), bench.kinds("a"))
    }

    @Test
    fun `a sentence stopped before its end is cut once and never spoken`() {
        val bench = Bench()
        val (_, heard) = bench.say("a", priority = 8)
        bench.advanceTo(SimSpeech.durationMs("k") - 1)
        bench.speech.stop()
        bench.advanceTo(10_000)
        assertEquals(listOf(UtteranceOutcome.CUT), heard.outcomes)
        assertEquals("no onSpoken after the stop either", 0, heard.spoken)
        assertEquals(listOf("speak", "cut"), bench.kinds("a"))
    }

    @Test
    fun `a system notice that ends with no frame after it leaves the ledger, its protection with it, and the one behind it starts`() {
        val bench = Bench()
        bench.speech.speakSystemNotice("protected", priority = Int.MAX_VALUE)
        bench.speech.speakSystemNotice("unprotected", priority = null)
        val ends = SimSpeech.durationMs("protected")
        bench.advanceTo(ends - 1)
        assertFalse("while it plays, a prompt is refused", bench.say("before", priority = 24).first)
        bench.advanceTo(ends)
        val second = bench.speech.ledgerIds.single()
        assertEquals("the queued one starts at once", listOf("notice", "start"), bench.kinds(second))
        assertEquals("ends", listOf("notice", "done"), bench.kinds("${SimSpeech.SYSTEM_NOTICE_PREFIX}1"))
        assertTrue("its protection went with it: a lower prompt is taken", bench.say("after", priority = 8).first)
        assertEquals("and flushes the unprotected one", listOf("notice", "start", "cut"), bench.kinds(second))
    }

    @Test
    fun `a queued system notice protects the ledger for 15 s from when it was queued, and once flushed never plays`() {
        val bench = Bench()
        // A sentence long enough to keep the notice queued past its protection.
        val longKey = "x".repeat(400)
        val (_, first) = bench.say("long", priority = 8, key = longKey)
        bench.advanceTo(100)
        bench.speech.speakSystemNotice("notice", priority = 16)
        val notice = "${SimSpeech.SYSTEM_NOTICE_PREFIX}1"
        assertEquals("queued behind it", listOf("long", notice), bench.speech.ledgerIds)
        bench.advanceTo(100 + SpeechManager.PROTECTED_NOTICE_MAX_MS)
        assertFalse("still protected 15 s after it was queued, though it never started", bench.say("middle", priority = 12).first)
        bench.advanceTo(100 + SpeechManager.PROTECTED_NOTICE_MAX_MS + 1)
        assertTrue("past it, a prompt above the playing one is taken", bench.say("higher", priority = 12).first)
        assertEquals("the long one is cut once", listOf(UtteranceOutcome.CUT), first.outcomes)
        bench.advanceTo(60_000)
        assertEquals("the flushed notice is dropped and never starts or ends", listOf("notice", "drop"), bench.kinds(notice))
        assertEquals("the long one is cut once and never ends", listOf(UtteranceOutcome.CUT), first.outcomes)
    }

    @Test
    fun `only a strictly higher sentence interrupts, equal, expired and not ready are refused, the interrupted one is cut once`() {
        val bench = Bench()
        val (_, low) = bench.say("low", priority = 8)
        bench.advanceTo(10)
        assertFalse("an equal one is refused", bench.say("equal", priority = 8).first)
        assertFalse("an expired one is refused", bench.say("expired", priority = 24, expiresAtMs = 5).first)
        val (taken, high) = bench.say("high", priority = 16)
        assertTrue("a strictly higher one is taken", taken)
        assertEquals("the one it interrupts is cut once", listOf(UtteranceOutcome.CUT), low.outcomes)
        bench.speech.engine(ready = false)
        assertEquals("an engine that stops being ready cuts what it was saying", listOf(UtteranceOutcome.CUT), high.outcomes)
        assertFalse("and refuses", bench.say("later", priority = 24).first)
        bench.advanceTo(10_000)
        assertEquals(listOf(UtteranceOutcome.CUT), low.outcomes)
        assertEquals(listOf(UtteranceOutcome.CUT), high.outcomes)
        assertEquals(0, low.spoken + high.spoken)
        assertEquals(
            listOf("refused(outranked)", "refused(expired)", "refused(not_ready)"),
            listOf("equal", "expired", "later").map { id -> bench.lines.single { it.id == id }.result },
        )
    }

    @Test
    fun `only readiness changing does not move the state, unavailable but ready, the first sentence taken makes it ready, once`() {
        for (first in listOf("prompt", "notice")) {
            val bench = Bench()
            val emitted = mutableListOf<SpeechEngineState>()
            val scope = CoroutineScope(Dispatchers.Unconfined)
            scope.launch { bench.speech.state.collect { emitted += it } }
            bench.speech.engine(SpeechEngineState.UNAVAILABLE, ready = false)
            bench.speech.engine(ready = true)
            assertEquals("$first: rebuilt, the state has not moved", listOf(SpeechEngineState.READY, SpeechEngineState.UNAVAILABLE), emitted)
            if (first == "prompt") bench.say("a", priority = 8) else bench.speech.speakSystemNotice("n", priority = null)
            bench.say("b", priority = 16)
            scope.cancel()
            assertEquals("$first: ready once", listOf(SpeechEngineState.READY, SpeechEngineState.UNAVAILABLE, SpeechEngineState.READY), emitted)
        }
    }
}
