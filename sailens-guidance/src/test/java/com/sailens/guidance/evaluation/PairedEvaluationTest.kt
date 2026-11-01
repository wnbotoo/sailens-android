package com.sailens.guidance.evaluation

import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.Severity
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.decision.CooldownManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.UUID

class PairedEvaluationTest {
    private val target = StageKey("p", Stage.CRITICAL)
    private val frozenKernel get() = ClosedLoopSpeakerBranches({ 150 }, heldAfterDone = false, frameSkewMs = 0)

    private fun offer(t: Long, id: String, durationMs: Long = 1_000, priority: Int = 3) = SpeakerModel.Offer(t, id, priority, t + 5_000, durationMs)

    private fun decision(offer: SpeakerModel.Offer?, credited: String = "p") =
        offer?.let { PairedBranchRun.Decision(it, if (it.id == credited) listOf(target) else emptyList(), onAccepted = {}, onRefused = {}) }

    private fun targetOf(deadlineMs: Long, appearedAtMs: Long = 0) = PairedEvaluation.Target(target, deadlineMs, appearedAtMs)

    private fun evaluated(
        deadlineMs: Long,
        frames: List<Long>,
        baseline: Map<Long, SpeakerModel.Offer>,
        arm: Map<Long, SpeakerModel.Offer>,
        samples: Int = 0,
        sweep: Boolean = true,
        seed: Long = 0x5A11E25L,
    ): PairedEvaluation {
        val run = PairedEvaluation({ 150 }, listOf(targetOf(deadlineMs)), emptyList(), frames.toLongArray(), samples, heldAfterDone = false, frameSkewMs = 0, sweep = sweep, seed = seed)
        for (t in frames) run.onFrame(t, { decision(baseline[t]) }, { decision(arm[t]) })
        run.finish()
        return run
    }

    private fun exact(deadlineMs: Long, frames: List<Long>, baseline: Map<Long, SpeakerModel.Offer>, arm: Map<Long, SpeakerModel.Offer>): PairedBranchRun.Result {
        val run = PairedBranchRun(frozenKernel, listOf(targetOf(deadlineMs)), emptyList())
        for (t in frames) run.onFrame(t, { decision(baseline[t]) }, { decision(arm[t]) })
        return run.finish()
    }

    @Test
    fun `an arm that changes nothing scores no regression, however close the ends fall`() {
        val offers = mapOf(0L to offer(0, "a", 1_010, 2), 1_000L to offer(1_000, "p", 1_000, 2))
        val frames = listOf(0L, 1_000L, 2_000L, 3_000L)
        val result = evaluated(2_500, frames, offers, offers, samples = 32).result(32)
        assertEquals(emptySet<StageKey>(), result.regressions)
        assertEquals("A runs long in some realization, in both arms, and nothing else differs", setOf(target), result.sameInputs)
        assertEquals(emptySet<StageKey>(), exact(2_500, frames, offers, offers).regressions)
    }

    @Test
    fun `the A, B, P case of R10 is a regression`() {
        // Only the arm says B. If B runs 40 ms long, P meets it still speaking and is refused; the baseline has no B to meet.
        val a = offer(0, "a", 1_000, 1)
        val b = offer(1_010, "b", 1_000, 1)
        val p = offer(2_020, "p", 1_000, 1)
        val frames = listOf(0L, 1_010L, 2_020L, 3_200L, 4_000L)
        val base = mapOf(0L to a, 2_020L to p)
        val arm = mapOf(0L to a, 1_010L to b, 2_020L to p)
        assertEquals(setOf(target), evaluated(3_600, frames, base, arm).result(0).regressions)
        assertEquals(setOf(target), exact(3_600, frames, base, arm).regressions)
    }

    @Test
    fun `a sentence ending just before the deadline counts by when it ended, not by the top of its interval`() {
        // The baseline's P ends at 1 000, the deadline is 1 100, the interval's top 1 150. The arm never says P.
        val p = offer(0, "p")
        val frames = listOf(0L, 2_000L)
        val run = evaluated(1_100, frames, mapOf(0L to p), emptyMap(), sweep = false)
        assertEquals(setOf(target), run.result(0, withFaults = false).regressions)
        assertEquals(setOf(target), exact(1_100, frames, mapOf(0L to p), emptyMap()).regressions)
    }

    @Test
    fun `nominal stage times are the sentence's own end`() {
        val p = offer(0, "p")
        val run = evaluated(5_000, listOf(0L, 2_000L, 6_000L), mapOf(0L to p), mapOf(0L to p), sweep = false)
        assertEquals(1_000L, run.nominalBaseKept()[target])
    }

    @Test
    fun `one sentence ending in a one-millisecond gap is found, whatever the samples do`() {
        // When A ends in (900, 901], B is refused at 900 and the arm's Q is taken at 901 and holds P off; the baseline speaks P.
        val a = offer(0, "a")
        val b = offer(900, "b")
        val p = offer(1_100, "p")
        val base = mapOf(0L to a, 900L to b, 1_100L to p)
        val arm = base + (901L to offer(901, "q"))
        val frames = listOf(0L, 900L, 901L, 1_100L, 2_300L, 2_600L)
        assertEquals(setOf(target), evaluated(2_500, frames, base, arm).result(0).regressions)
        assertEquals(setOf(target), exact(2_500, frames, base, arm).regressions)
        assertEquals("a gap drawn uniformly is no rare event", setOf(target), evaluated(2_500, frames, base, arm, samples = 32, sweep = false).result(32, withFaults = false).regressions)
        assertEquals(setOf(target), evaluated(2_500, frames, base, arm, samples = 32, sweep = false, seed = 0x7E57C0DEL).result(32, withFaults = false).regressions)
    }

    private fun event(category: EventCategory, key: String, t: Long) = SceneEvent(
        UUID.randomUUID(), t, category, EventPriority.HIGH, "probe", emptyMap(), t + 5_000, key, setOf(key), emptyList(), 1f, Severity.MODERATE, emptyList(), DistanceLevel.MEDIUM, null,
    )

    private fun historyDecision(cooldown: CooldownManager, t: Long, experimental: Boolean, record: SceneEvent): PairedBranchRun.Decision? = when {
        t == 0L -> decision(offer(0, "a", 1_010))
        t == 1_000L -> {
            cooldown.recordEvent(record, t)
            PairedBranchRun.Decision(offer(t, "r"), emptyList(), onAccepted = { it.confirm(record.id) }, onRefused = { it.revoke(record.id) })
        }
        t == 20_000L && experimental && cooldown.filter(listOf(record), t).isEmpty() -> decision(offer(t, "q"))
        t == 20_100L -> decision(offer(t, "p"))
        else -> null
    }

    @Test
    fun `an old common error reaches a stage through a long cooldown`() {
        // Whether A ran long decides whether R leaves a 60 s record; 19 s later the arm acts on that record, and only then.
        val record = event(EventCategory.GROUND_UNRECOGNIZED, "ground_unrecognized", 1_000)
        val frames = listOf(0L, 1_000L, 20_000L, 20_100L, 22_000L, 24_000L)
        val run = PairedEvaluation({ 150 }, listOf(targetOf(23_000)), emptyList(), frames.toLongArray(), 0, heldAfterDone = false, frameSkewMs = 0)
        val reference = PairedBranchRun(frozenKernel, listOf(targetOf(23_000)), emptyList())
        for (t in frames) {
            run.onFrame(t, { historyDecision(it, t, false, record) }, { historyDecision(it, t, true, record) })
            reference.onFrame(t, { historyDecision(it, t, false, record) }, { historyDecision(it, t, true, record) })
        }
        run.finish()
        assertEquals(setOf(target), reference.finish().regressions)
        assertEquals(setOf(target), run.result(0).regressions)
    }

    @Test
    fun `an arm that withholds a prompt is a regression, and nothing tells the run the arms differ`() {
        val p = offer(0, "p")
        val result = evaluated(1_500, listOf(0L, 2_000L), mapOf(0L to p), emptyMap(), samples = 32).result(32)
        assertEquals(setOf(target), result.regressions)
    }

    @Test
    fun `the same words crediting a stage in one arm only count for that arm`() {
        val p = offer(0, "p")
        val run = PairedEvaluation({ 150 }, listOf(targetOf(1_500)), emptyList(), longArrayOf(0, 2_000), 8, heldAfterDone = false, frameSkewMs = 0)
        for (t in listOf(0L, 2_000L)) {
            run.onFrame(
                t,
                { if (t == 0L) decision(p) else null },
                { if (t == 0L) decision(p, credited = "nobody") else null },
            )
        }
        run.finish()
        assertEquals(setOf(target), run.result(8).regressions)
    }

    @Test
    fun `a stage lost in the same realization in both arms, with different paths, is told apart from a regression`() {
        val a = offer(5_000, "a", 1_010, 2)
        val b = offer(0, "b", 500, 1)
        val p = offer(6_000, "p", 1_000, 2)
        val frames = listOf(0L, 5_000L, 6_000L, 7_000L, 8_000L)
        val result = evaluated(7_500, frames, mapOf(5_000L to a, 6_000L to p), mapOf(0L to b, 5_000L to a, 6_000L to p), samples = 32).result(32)
        assertEquals(emptySet<StageKey>(), result.regressions)
        assertEquals("the arms had differed since before the stage appeared at 0", setOf(target), result.bothLostApart)
    }

    private fun randomStream(random: Random): Triple<List<Long>, Map<Long, SpeakerModel.Offer>, Map<Long, SpeakerModel.Offer>> {
        val frames = (0L..6_000L step 50L).toList()
        val base = HashMap<Long, SpeakerModel.Offer>()
        var t = 0L
        var n = 0
        while (t < 4_000) {
            base[t] = offer(t, "s${n++}", 400L + random.nextInt(800), 1 + random.nextInt(3))
            t += 50L * (2 + random.nextInt(24))
        }
        base[4_000] = offer(4_000, "p", 800, 2)
        val arm = HashMap(base)
        repeat(1 + random.nextInt(3)) {
            val at = 50L * random.nextInt(80)
            if (at !in arm) arm[at] = offer(at, "x$at", 400L + random.nextInt(800), 1 + random.nextInt(3))
        }
        return Triple(frames, base, arm)
    }

    @Test
    fun `against the exact reference on random streams, nothing is invented and single faults find what one sentence can do`() {
        val random = Random(20)
        var exactRegressions = 0
        var found = 0
        repeat(200) {
            val (frames, base, arm) = randomStream(random)
            val reference = exact(5_500, frames, base, arm)
            assertTrue("the reference settles these", !reference.capHit)
            val result = evaluated(5_500, frames, base, arm, samples = 32).result(32)
            assertTrue("nothing the exact run does not have", (result.regressions - reference.regressions).isEmpty())
            exactRegressions += reference.regressions.size
            found += result.regressions.size
        }
        println("random streams: exact regressions $exactRegressions, found $found")
        assertTrue("the streams exercise it", exactRegressions >= 20)
        assertEquals(exactRegressions, found)
    }

    @Test
    fun `the first n samples of a larger run are a run of n samples`() {
        val random = Random(7)
        repeat(50) {
            val (frames, base, arm) = randomStream(random)
            val large = evaluated(5_500, frames, base, arm, samples = 32, sweep = false)
            for (n in listOf(0, 8, 16)) {
                val alone = evaluated(5_500, frames, base, arm, samples = n, sweep = false).result(n, withFaults = false)
                val prefix = large.result(n, withFaults = false)
                assertEquals(alone.kept, prefix.kept)
                assertEquals(alone.regressions, prefix.regressions)
                assertEquals(alone.bothLostApart, prefix.bothLostApart)
                assertEquals(alone.sameInputs, prefix.sameInputs)
                assertEquals(alone.armLostSomewhere, prefix.armLostSomewhere)
            }
        }
    }
}
