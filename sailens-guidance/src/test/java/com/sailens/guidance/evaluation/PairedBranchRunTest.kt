package com.sailens.guidance.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedBranchRunTest {
    private val target = StageKey("p", Stage.CRITICAL)

    private fun offer(t: Long, id: String, priority: Int, durationMs: Long) = SpeakerModel.Offer(t, id, priority, t + 5_000, durationMs)

    private fun decision(offer: SpeakerModel.Offer, credits: List<StageKey> = emptyList()) =
        PairedBranchRun.Decision(offer, credits, onAccepted = {}, onRefused = {})

    private fun run(
        deadlineMs: Long,
        frames: List<Long>,
        baseline: Map<Long, SpeakerModel.Offer>,
        arm: Map<Long, SpeakerModel.Offer>,
        credited: String,
        differences: Set<Long>,
    ): PairedBranchRun.Result {
        val run = PairedBranchRun(
            ClosedLoopSpeakerBranches(heldAfterDone = false, frameSkewMs = 0),
            listOf(BranchLoadMeasure.Target(target, emptySet(), deadlineMs)),
            emptyList(),
            inWindow = { t -> differences.any { t in (it - 8_000)..it } },
            differsNear = { t -> differences.any { it in (t - 8_000)..t } },
        )
        for (t in frames) {
            run.onFrame(
                t,
                { baseline[t]?.let { o -> decision(o, if (o.id == credited) listOf(target) else emptyList()) } },
                { arm[t]?.let { o -> decision(o, if (o.id == credited) listOf(target) else emptyList()) } },
            )
        }
        return run.finish()
    }

    private fun sampled(
        deadlineMs: Long,
        frames: List<Long>,
        baseline: Map<Long, SpeakerModel.Offer>,
        arm: Map<Long, SpeakerModel.Offer>,
        credited: String,
        differences: Set<Long>,
    ): PairedBranchRun.Result {
        val run = SampledPairedRun(
            ClosedLoopSpeakerBranches(heldAfterDone = false, frameSkewMs = 0),
            listOf(BranchLoadMeasure.Target(target, emptySet(), deadlineMs)),
            emptyList(),
            differs = { it in differences },
            differsNear = { t -> differences.any { it in (t - 8_000)..t } },
            samples = 32,
            frameSkewMs = 0,
        )
        for (t in frames) {
            run.onFrame(
                t,
                { baseline[t]?.let { o -> decision(o, if (o.id == credited) listOf(target) else emptyList()) } },
                { arm[t]?.let { o -> decision(o, if (o.id == credited) listOf(target) else emptyList()) } },
            )
        }
        run.finish()
        return run.result(32)
    }

    @Test
    fun `by samples, an arm that changes nothing scores nothing, and a stage lost in both is not a regression`() {
        val a = offer(0, "a", 2, 1_010)
        val p = offer(1_000, "p", 2, 1_000)
        val offers = mapOf(0L to a, 1_000L to p)
        val result = sampled(2_500, listOf(0L, 1_000L, 2_000L, 3_000L), offers, offers, "p", emptySet())
        assertEquals(emptySet<StageKey>(), result.regressions)
        assertTrue("A runs long in some sample, in both arms", target in result.armLostAnyBranch)
    }

    @Test
    fun `by samples, an arm that drops the prompt is a regression`() {
        val p = offer(0, "p", 3, 1_000)
        val result = sampled(1_500, listOf(0L, 1_000L, 2_000L), mapOf(0L to p), emptyMap(), "p", setOf(0L))
        assertEquals(setOf(target), result.regressions)
    }

    @Test
    fun `an arm that changes nothing scores no regression, however close the ends fall`() {
        // A ends 10 ms around the frame at 1 000, P arrives there at the same priority: in the realizations
        // where A is still speaking, P is refused, in both arms alike.
        val offers = mapOf(0L to offer(0, "a", 2, 1_010), 1_000L to offer(1_000, "p", 2, 1_000))
        val result = run(2_500, listOf(0L, 1_000L, 2_000L, 3_000L), offers, offers, "p", differences = emptySet())
        assertEquals(emptySet<StageKey>(), result.regressions)
        assertEquals(1, result.maxBranches)
    }

    @Test
    fun `the A, B, P case of R10 is still a regression`() {
        // Only the arm says B. If B runs 40 ms long, P meets it still speaking and is refused; the baseline has no B to meet.
        val a = offer(0, "a", 1, 1_000)
        val b = offer(1_010, "b", 1, 1_000)
        val p = offer(2_020, "p", 1, 1_000)
        val frames = listOf(0L, 1_010L, 2_020L, 3_200L, 4_000L)
        val result = run(3_600, frames, mapOf(0L to a, 2_020L to p), mapOf(0L to a, 1_010L to b, 2_020L to p), "p", differences = setOf(1_010L))
        assertEquals(setOf(target), result.regressions)
        assertEquals("by samples, as well", setOf(target), sampled(3_600, frames, mapOf(0L to a, 2_020L to p), mapOf(0L to a, 1_010L to b, 2_020L to p), "p", setOf(1_010L)).regressions)
    }

    @Test
    fun `a stage lost in the same realization in both arms is not the arm's regression`() {
        // The arm's B is far from P; the only way P is refused is A running long, which the baseline suffers too.
        val a = offer(5_000, "a", 2, 1_010)
        val b = offer(0, "b", 1, 500)
        val p = offer(6_000, "p", 2, 1_000)
        val frames = listOf(0L, 5_000L, 6_000L, 7_000L, 8_000L)
        val result = run(7_500, frames, mapOf(5_000L to a, 6_000L to p), mapOf(0L to b, 5_000L to a, 6_000L to p), "p", differences = setOf(0L))
        assertEquals(emptySet<StageKey>(), result.regressions)
        assertTrue(target in result.armLostAnyBranch)
    }

    @Test
    fun `an arm that drops the prompt outright is a regression`() {
        val p = offer(0, "p", 3, 1_000)
        val result = run(1_500, listOf(0L, 1_000L, 2_000L), mapOf(0L to p), emptyMap(), "p", differences = setOf(0L))
        assertEquals(setOf(target), result.regressions)
    }
}
