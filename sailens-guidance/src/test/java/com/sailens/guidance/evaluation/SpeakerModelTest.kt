package com.sailens.guidance.evaluation

import com.sailens.guidance.evaluation.SpeakerModel.Offer
import com.sailens.guidance.evaluation.SpeakerModel.Queued
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** det-first plan D1.4: the speaker model follows SpeechManager's arbitration. */
class SpeakerModelTest {

    private val model = SpeakerModel()
    private fun byId(inputs: List<SpeakerModel.Input>) = model.run(inputs).associateBy { it.id }

    @Test
    fun `only a strictly higher priority interrupts, and the interrupted one does not complete`() {
        val p = byId(
            listOf(
                Offer(0, "high", priority = 2, expiresAtMs = 3_000, durationMs = 1_500),
                Offer(500, "same", priority = 2, expiresAtMs = 3_500, durationMs = 1_500),
                Offer(700, "critical", priority = 3, expiresAtMs = 3_700, durationMs = 1_500),
            ),
        )
        assertEquals("outranked", p.getValue("same").reason)
        assertFalse(p.getValue("high").completed)
        assertEquals(700L, p.getValue("high").endAtMs)
        assertTrue(p.getValue("critical").completed)
        assertEquals(2_200L, p.getValue("critical").endAtMs)
    }

    @Test
    fun `speech past its deadline no longer outranks, and an expired offer is refused`() {
        val p = byId(
            listOf(
                Offer(0, "long", priority = 3, expiresAtMs = 1_000, durationMs = 5_000),
                Offer(1_200, "after", priority = 1, expiresAtMs = 4_000, durationMs = 1_000),
                Offer(5_000, "late", priority = 3, expiresAtMs = 4_000, durationMs = 1_000),
            ),
        )
        assertTrue(p.getValue("after").accepted)
        assertEquals("expired", p.getValue("late").reason)
    }

    @Test
    fun `notices queue behind, a prioritized one holds off guidance, and an unready engine refuses`() {
        val p = byId(
            listOf(
                Offer(0, "guidance", priority = 2, expiresAtMs = 3_000, durationMs = 1_000),
                Queued(100, "notice", priority = Int.MAX_VALUE, durationMs = 2_000),
                Offer(1_500, "blocked-by-notice", priority = 3, expiresAtMs = 4_500, durationMs = 1_000),
                SpeakerModel.EngineReady(4_000, ready = false),
                Offer(4_100, "not-ready", priority = 3, expiresAtMs = 7_000, durationMs = 1_000),
            ),
        )
        assertEquals(1_000L, p.getValue("notice").startAtMs)
        assertEquals("outranked", p.getValue("blocked-by-notice").reason)
        assertEquals("engine_not_ready", p.getValue("not-ready").reason)
    }

    @Test
    fun `calibration counts agreement and CRITICAL misjudgements`() {
        val predictions = model.run(
            listOf(
                Offer(0, "a", 3, 3_000, 1_000),
                Offer(100, "b", 3, 3_100, 1_000),
            ),
        )
        val c = SpeakerModel.Calibration(
            predictions,
            mapOf(
                "a" to SpeakerModel.Actual(accepted = true, startedAtMs = 30.0, doneAtMs = 1_100.0, critical = true),
                "b" to SpeakerModel.Actual(accepted = true, critical = true),
            ),
        )
        assertEquals(1, c.acceptedBoth)
        assertEquals(1, c.refusedPredictedOnly)
        assertEquals(1, c.criticalMisjudged)
        assertTrue(c.render("test").contains("DESCRIPTIVE ONLY"))
    }
}
