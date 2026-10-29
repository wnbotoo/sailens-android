package com.sailens.guidance.evaluation

import com.sailens.guidance.evaluation.SpeakerModel.Offer
import com.sailens.guidance.evaluation.SpeakerModel.Queued
import com.sailens.guidance.evaluation.SpeakerRobustness.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Threshold note R10: kept only if kept under every independent end-time error within ε. */
class SpeakerRobustnessTest {

    // Review round 3's case: three hazards, one offer each, the frozen table's 1 237 ms.
    private val abp = listOf(
        Offer(0, "a", 3, 3_000, 1_237),
        Offer(1_250, "b", 3, 4_250, 1_237),
        Offer(2_500, "p", 3, 5_500, 1_237),
    )
    private val deadline = 3_737L + 500

    @Test
    fun `a sentence kept under every uniform shift is still lost when one other sentence alone runs long`() {
        // Nominal and the four uniform runs of revision 3 all keep p...
        for (shift in listOf(0L, 150L, -150L)) {
            val p = SpeakerModel().run(abp.map { it.copy(durationMs = it.durationMs + shift) }).first { it.id == "p" }
            assertTrue("shift $shift", p.completed && p.endAtMs!! <= deadline)
        }
        val late = SpeakerModel(startLatencyMs = 150).run(abp.map { it.copy(durationMs = it.durationMs + 150) }).first { it.id == "p" }
        assertTrue(late.completed && late.endAtMs!! <= deadline)
        // ...but b alone 40 ms longer is still speaking when p arrives.
        val bLonger = SpeakerModel().run(abp.map { if (it.id == "b") it.copy(durationMs = it.durationMs + 40) else it }).first { it.id == "p" }
        assertEquals(false, bLonger.accepted)

        assertEquals(Verdict.LOST, SpeakerRobustness(epsilonMs = 150).verdict(abp, "p", deadline))
        assertEquals(Verdict.KEPT, SpeakerRobustness(epsilonMs = 0).verdict(abp, "p", deadline))
    }

    @Test
    fun `a sentence with room on every side is kept, and one too close to its deadline is not`() {
        val alone = listOf(Offer(0, "a", 3, 3_000, 1_237), Offer(2_000, "p", 3, 5_000, 1_237))
        assertEquals(Verdict.KEPT, SpeakerRobustness().verdict(alone, "p", 2_000L + 1_237 + 500))
        assertEquals(Verdict.LOST, SpeakerRobustness().verdict(alone, "p", 2_000L + 1_237 + 100))
    }

    @Test
    fun `a strictly higher prompt arriving near the end may cut it off`() {
        val inputs = listOf(Offer(0, "p", 2, 3_000, 1_285), Offer(1_385, "higher", 3, 4_000, 1_000))
        assertEquals(Verdict.KEPT, SpeakerRobustness(epsilonMs = 0).verdict(inputs, "p", 10_000))
        assertEquals(Verdict.LOST, SpeakerRobustness(epsilonMs = 150).verdict(inputs, "p", 10_000))
    }

    @Test
    fun `too many branches at once is undetermined, not kept`() {
        // b's offer already splits (a ended or not) before p is reached.
        assertEquals(Verdict.UNDETERMINED, SpeakerRobustness(epsilonMs = 150, maxBranches = 1).verdict(abp, "p", deadline))
    }

    @Test
    fun `with no error it agrees with the speaker model`() {
        val inputs = listOf(
            Offer(0, "a", 2, 3_000, 1_500),
            Offer(500, "b", 2, 3_500, 1_000),
            Offer(900, "c", 3, 3_900, 1_200),
            Queued(1_000, "notice", priority = null, durationMs = 800),
            Offer(2_200, "d", 1, 5_200, 1_000),
            Offer(4_000, "e", 2, 4_100, 900),
            Offer(4_200, "f", 3, 4_150, 900),
        )
        val predictions = SpeakerModel().run(inputs)
        for (p in predictions.filter { it.id != "notice" }) {
            val expected = if (p.completed) Verdict.KEPT else Verdict.LOST
            assertEquals(p.id, expected, SpeakerRobustness(epsilonMs = 0).verdict(inputs, p.id, Long.MAX_VALUE))
        }
    }

    @Test
    fun `M2 has no deadline - a NEAR wording spoken to the end after the crossing still counts`() {
        val near = listOf(Offer(0, "other", 3, 3_000, 1_237), Offer(2_000, "near", 3, 5_000, 1_982))
        val crossingMs = 3_000L
        // Spoken to the end at about 3 982 ms, after the crossing: no M1 success, still an M2 success.
        assertEquals(Verdict.LOST, SpeakerRobustness().verdict(near, "near", crossingMs))
        val m2 = SpeakerRobustness().check(near, "near")
        assertEquals(Verdict.KEPT, m2.verdict)
        // M1 classifies the latest end over every branch.
        assertEquals(2_000L + 1_982 + 150, m2.latestEndMs)
    }
}
