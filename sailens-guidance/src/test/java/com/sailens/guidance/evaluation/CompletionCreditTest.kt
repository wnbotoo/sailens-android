package com.sailens.guidance.evaluation

import com.sailens.guidance.trace.capture.RegressionUtteranceRecord
import com.sailens.guidance.trace.capture.UtteranceRecordEvents as E
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * det-first plan D1.3-3: completion is credited to the logical prompt, from any of its attempts,
 * and is never taken back; each attempt fails at most once; contradictory evidence is kept and
 * flagged. Sequences as the speech side records them, including the orders the plan names.
 */
class CompletionCreditTest {

    private var t = 0L
    private val records = mutableListOf<RegressionUtteranceRecord>()

    private fun rec(
        event: String,
        attempt: String,
        logical: String? = "p",
        gen: Long? = 1,
        reason: String? = null,
        previous: String? = null,
        interrupted: Boolean? = null,
    ) {
        t += 10
        records += RegressionUtteranceRecord(
            event = event, attemptId = attempt, logicalId = logical, engineGeneration = gen, elapsedRealtimeNanos = t,
            reason = reason, previousAttemptId = previous, interrupted = interrupted,
        )
    }

    private fun credit() = CompletionCredit.of(records)

    @Test
    fun `a submitted attempt that finishes completes the prompt`() {
        rec(E.SUBMITTED, "a")
        rec(E.STARTED, "a", logical = "p")
        rec(E.DONE, "a")

        val p = credit()["p"]!!
        assertEquals(30L, p.completedAtNanos)
        assertEquals(20L, p.firstStartedAtNanos)
        assertEquals(0, p.failedAttempts)
    }

    @Test
    fun `an old attempt's stop arriving after the new attempt started fails only the old one`() {
        rec(E.SUBMITTED, "a", logical = "pa")
        rec(E.SUBMITTED, "b", logical = "pb")
        rec(E.SUPERSEDED, "a", logical = "pa")
        rec(E.STARTED, "b", logical = "pb")
        rec(E.STOPPED, "a", logical = null, interrupted = true) // the speech side could still place it by attempt id
        rec(E.DONE, "b", logical = "pb")

        val c = credit()
        assertFalse(c["pa"]!!.completed)
        assertEquals("superseded and the later stop are one failure", 1, c["pa"]!!.failedAttempts)
        assertEquals(listOf("superseded"), c["pa"]!!.failures)
        assertTrue(c["pb"]!!.completed)
        assertEquals(0, c.conflicts)
    }

    @Test
    fun `done, then handed back, then the hand-back fails -- still completed`() {
        // The engine finished A; before the main thread had forgotten it, a withdrawal handed it back
        // as B, and the engine refused B.
        rec(E.SUBMITTED, "a")
        rec(E.DONE, "a")
        rec(E.REQUEUE_FAILED, "b", previous = "a")

        val p = credit()["p"]!!
        assertTrue("a completion is never taken back", p.completed)
        assertEquals(20L, p.completedAtNanos)
        assertEquals(1, p.failedAttempts)
    }

    @Test
    fun `a successful hand-back completes the prompt when the new attempt finishes`() {
        rec(E.SUBMITTED, "a")
        rec(E.STARTED, "a")
        rec(E.REQUEUED, "a~1", previous = "a")
        rec(E.STOPPED, "a", interrupted = true)
        rec(E.STARTED, "a~1")
        rec(E.DONE, "a~1")

        val p = credit()["p"]!!
        assertEquals(60L, p.completedAtNanos)
        assertEquals(2, p.attempts.size)
        assertEquals(1, p.failedAttempts)
    }

    @Test
    fun `a failed hand-back with nothing finished leaves the prompt not completed`() {
        rec(E.SUBMITTED, "a")
        rec(E.STARTED, "a")
        rec(E.REQUEUE_FAILED, "a~1", previous = "a")
        rec(E.STOPPED, "a", interrupted = true)

        val p = credit()["p"]!!
        assertFalse(p.completed)
        assertEquals(listOf("stopped", "requeue_failed"), p.failures)
    }

    @Test
    fun `a done after a flush still counts, and the earliest done wins`() {
        rec(E.SUBMITTED, "a")
        rec(E.REQUEUED, "a~1", previous = "a")
        rec(E.SUPERSEDED, "a")
        rec(E.DONE, "a") // late: the audible output had finished
        rec(E.DONE, "a~1")

        val p = credit()["p"]!!
        assertEquals(40L, p.completedAtNanos)
        assertFalse(p.evidenceConflict)
    }

    @Test
    fun `contradictory engine evidence on one attempt is kept and flagged, not resolved`() {
        rec(E.SUBMITTED, "a")
        rec(E.DONE, "a")
        rec(E.STOPPED, "a", interrupted = true)

        val p = credit()["p"]!!
        assertTrue(p.evidenceConflict)
        assertFalse("neither reading is picked", p.completed)
        assertEquals(0, p.failedAttempts)
        assertEquals(3, p.attempts.single().records.size)
        assertEquals(1, credit().conflicts)
    }

    @Test
    fun `an engine restart -- a done from another instance is a conflict, the old instance's own done is valid`() {
        rec(E.SUBMITTED, "a", gen = 1)
        rec(E.UNKNOWN_TERMINAL, "a", gen = 1, reason = "engine_shut_down")
        rec(E.DONE, "a", gen = 1)
        rec(E.SUBMITTED, "b", logical = "q", gen = 2)
        rec(E.DONE, "b", logical = "q", gen = 3)

        val c = credit()
        assertTrue("the old instance's own late done", c["p"]!!.completed)
        assertTrue(c["q"]!!.evidenceConflict)
        assertFalse(c["q"]!!.completed)
    }

    @Test
    fun `the session ended before any outcome -- not completed, one failure`() {
        rec(E.SUBMITTED, "a")
        rec(E.UNKNOWN_TERMINAL, "a", reason = "session_ended")

        val p = credit()["p"]!!
        assertFalse(p.completed)
        assertEquals(listOf("unknown_terminal(session_ended)"), p.failures)
    }

    @Test
    fun `refused before the engine -- never accepted, a failure with its reason`() {
        rec(E.REFUSED, "p", gen = null, reason = "outranked")

        val p = credit()["p"]!!
        assertTrue(p.neverAccepted)
        assertEquals(listOf("refused(outranked)"), p.failures)
        assertNull(p.completedAtNanos)
    }

    @Test
    fun `a screen-reader record is not an attempt`() {
        rec(E.SCREEN_READER_PUBLISHED, "p", gen = null)
        assertNull(credit()["p"])
    }
}
