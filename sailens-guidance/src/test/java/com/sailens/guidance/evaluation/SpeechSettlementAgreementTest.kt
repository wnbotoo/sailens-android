package com.sailens.guidance.evaluation

import com.sailens.guidance.trace.capture.RegressionUtteranceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `UtteranceSettlementTest` in sailens-output shows of the live speech path, against the frozen
 * scoring and speaker model: the two modules cannot see each other, so the shared cases are written out
 * in both.
 */
class SpeechSettlementAgreementTest {

    private fun record(event: String, id: String, atMs: Long) = RegressionUtteranceRecord(
        event = event,
        attemptId = id,
        logicalId = id,
        engineGeneration = 1L,
        elapsedRealtimeNanos = atMs * 1_000_000,
    )

    private fun accepted(id: String, atMs: Long) = listOf(record("submitted", id, atMs), record("started", id, atMs))

    @Test
    fun `an end before the flush is heard, as the live path says SPOKEN`() {
        val credit = CompletionCredit.of(accepted("a", 0) + record("done", "a", 1001) + record("superseded", "a", 1002))
        assertTrue(credit["a"]!!.completed)
        assertFalse(credit["a"]!!.evidenceConflict)
    }

    @Test
    fun `an end after the flush is heard, as the live path says HEARD_AFTER_CUT`() {
        val credit = CompletionCredit.of(accepted("a", 0) + record("superseded", "a", 1000) + record("done", "a", 1001))
        assertTrue(credit["a"]!!.completed)
        assertFalse(credit["a"]!!.evidenceConflict)
    }

    @Test
    fun `no end is not heard, as the live path says CUT`() {
        val credit = CompletionCredit.of(accepted("a", 0) + record("superseded", "a", 1000) + record("stopped", "a", 1001))
        assertFalse(credit["a"]!!.completed)
    }

    /**
     * The sentence's end is somewhere in [1108, 1408] and the frame comes at 1460: every reading of the frozen
     * model has A over and B accepted. The live path agrees because the frame settles A first
     * (`UtteranceSettlementTest`: "an end the posted task has not reached").
     */
    @Test
    fun `a frame past the whole end interval finds the speaker free in the frozen model too`() {
        val inputs = listOf(
            SpeakerModel.Offer(150, "a", 8, 10_000, 1108),
            SpeakerModel.Offer(1460, "b", 8, 10_000, 500),
        )
        assertEquals(SpeakerRobustness.Verdict.KEPT, SpeakerRobustness().check(inputs, "b").verdict)
    }

    /**
     * Where they part: the frozen model compares at the decision clock. Here every reading of it has A still
     * on at 1107, so B refused and C spoken; on the live path A really ended at 1108, the frame came at 1109,
     * B was accepted and C refused (`UtteranceSettlementTest`: "two milliseconds"). So the closed-loop
     * branches widen the comparison by the time from decision to frame, and this must come out LOST there.
     */
    @Test
    fun `the frozen model alone misses what the time from decision to frame can do`() {
        val inputs = listOf(
            SpeakerModel.Offer(0, "a", 8, 10_000, 1258),
            SpeakerModel.Offer(1107, "b", 8, 10_000, 1285),
            SpeakerModel.Offer(1600, "c", 8, 10_000, 1285),
        )
        val outcome = SpeakerRobustness().check(inputs, "c")
        assertEquals(SpeakerRobustness.Verdict.KEPT, outcome.verdict)
        assertEquals(3035L, outcome.latestEndMs)
    }

    /** The comparison the live path makes for a frame at t: ended exactly when the end time is <= t. */
    @Test
    fun `the frozen model's speaker is free at a frame exactly from the sentence's end time on`() {
        for (t in listOf(1399L, 1400L, 1401L)) {
            val out = SpeakerModel().run(
                listOf(
                    SpeakerModel.Offer(0, "a", 8, 10_000, 1400),
                    SpeakerModel.Offer(t, "b", 8, 10_000, 500),
                ),
            ).associateBy { it.id }
            assertEquals("frame at $t", t >= 1400, out.getValue("b").accepted)
        }
    }
}
