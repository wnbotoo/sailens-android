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

    private val guard = StageKey("guard", Stage.CRITICAL)

    private fun rejoining(cooldown: CooldownManager, t: Long, experimental: Boolean, record: SceneEvent): PairedBranchRun.Decision? = when {
        t == 0L -> decision(offer(0, "a", 1_010))
        t == 1_000L -> {
            cooldown.recordEvent(record, t)
            PairedBranchRun.Decision(offer(t, "r", 500), emptyList(), onAccepted = { it.confirm(record.id) }, onRefused = { it.revoke(record.id) })
        }
        t == 2_000L && experimental && cooldown.filter(listOf(record), t).isEmpty() -> decision(offer(t, "q"))
        else -> null
    }

    @Test
    fun `a path back on the nominal state keeps what it did before, and a target that changes nothing changes no verdict`() {
        // If A ends by 1 000 the record stands and the arm alone says Q at 2 000. The record then dies out and
        // both arms are back on the nominal state long before P's deadline, which neither speaks for.
        val frames = listOf(0L, 1_000L, 2_000L, 3_000L, 22_000L, 25_000L)
        val p = PairedEvaluation.Target(target, 24_000, 0)
        val record = event(EventCategory.OBSTACLE, "obstacle_PRIMARY_CENTER", 1_000)
        val alone = PairedEvaluation({ 150 }, listOf(p), emptyList(), frames.toLongArray(), 0, heldAfterDone = false, frameSkewMs = 0)
        val anchored = PairedEvaluation({ 150 }, listOf(p, PairedEvaluation.Target(guard, 60_000, 0)), emptyList(), frames.toLongArray(), 0, heldAfterDone = false, frameSkewMs = 0)
        for (run in listOf(alone, anchored)) {
            for (t in frames) run.onFrame(t, { rejoining(it, t, false, record) }, { rejoining(it, t, true, record) })
            run.finish()
        }
        assertEquals(setOf(target), alone.result(0).bothLostApart)
        assertEquals(emptySet<StageKey>(), alone.result(0).sameInputs)
        assertEquals(setOf(target), anchored.result(0).bothLostApart - guard)
        assertEquals("the nominal path alone has the arms the same throughout", setOf(target), alone.result(0, withFaults = false).sameInputs)
    }

    private fun queuedRegression(frames: List<Long>, deadlineMs: Long, queued: List<SpeakerModel.Queued>, baseline: Map<Long, SpeakerModel.Offer>, arm: Map<Long, SpeakerModel.Offer>) {
        val run = PairedEvaluation({ 150 }, listOf(targetOf(deadlineMs)), queued, frames.toLongArray(), 0, heldAfterDone = false, frameSkewMs = 0)
        val reference = PairedBranchRun(frozenKernel, listOf(targetOf(deadlineMs)), queued)
        for (t in frames) {
            run.onFrame(t, { decision(baseline[t]) }, { decision(arm[t]) })
            reference.onFrame(t, { decision(baseline[t]) }, { decision(arm[t]) })
        }
        run.finish()
        assertEquals(setOf(target), reference.finish().regressions)
        assertEquals("one sentence's error shows it", setOf(target), run.result(0, withPairs = false).regressions)
    }

    @Test
    fun `a queued sentence's own error is a fault, in a one-millisecond gap`() {
        val b = offer(900, "b")
        val p = offer(1_100, "p")
        val base = mapOf(900L to b, 1_100L to p)
        val arm = base + (901L to offer(901, "q"))
        queuedRegression(listOf(0L, 900L, 901L, 1_100L, 2_300L, 2_600L), 2_500, listOf(SpeakerModel.Queued(0, "a", 3, 1_000)), base, arm)
        queuedRegression(buildList { add(0L); addAll(850L..1_150L); add(2_300L); add(2_600L) }, 2_500, listOf(SpeakerModel.Queued(0, "a", 3, 1_000)), base, arm)
    }

    @Test
    fun `an error moves what is queued behind the sentence, so the moments that matter move back with it`() {
        // Only A ending in (900, 901] puts the queued sentence's end in (1 900, 1 901]: B refused, the arm's Q taken, P held off.
        val a = offer(0, "a")
        val b = offer(1_900, "b")
        val p = offer(2_100, "p")
        val base = mapOf(0L to a, 1_900L to b, 2_100L to p)
        val arm = base + (1_901L to offer(1_901, "q"))
        val queued = SpeakerModel.Queued(10, "queued", 3, 1_000)
        val frames = listOf(0L, 10L, 1_900L, 1_901L, 2_100L, 3_300L, 3_600L)
        queuedRegression(frames, 3_500, listOf(queued), base, arm)
        // The frozen model on that one realization agrees.
        val baseInputs = listOf(offer(0, "a", 901), queued, b, p)
        val completed = { inputs: List<SpeakerModel.Input> -> SpeakerModel().run(inputs).single { it.id == "p" }.completed }
        assertTrue(completed(baseInputs))
        assertTrue(!completed(baseInputs + offer(1_901, "q")))
    }

    @Test
    fun `a queued sentence reaches the speaker at its own time, not at the next frame`() {
        // A ends at 1 000; the sentence queued at 900 starts then, though the first frame to see it is at 1 500.
        val a = offer(0, "a")
        val queued = SpeakerModel.Queued(900, "queued", 3, 1_000)
        val frames = listOf(0L, 1_500L, 1_950L, 2_050L, 3_000L)
        val p = offer(1_950, "p")
        val run = evaluated(5_000, frames, mapOf(0L to a, 1_950L to p), mapOf(0L to a, 1_950L to p), sweep = false)
        assertEquals("without a queue, P is taken", 2_950L, run.nominalBaseKept()[target])
        val withQueued = PairedEvaluation({ 150 }, listOf(targetOf(5_000)), listOf(queued), frames.toLongArray(), 0, heldAfterDone = false, frameSkewMs = 0, sweep = false)
        for (t in frames) withQueued.onFrame(t, { decision(mapOf(0L to a, 1_950L to p)[t]) }, { decision(mapOf(0L to a, 1_950L to p)[t]) })
        withQueued.finish()
        assertEquals("queued behind A, it still speaks at 1 950 and holds P off", null, withQueued.nominalBaseKept()[target])
        assertTrue(!SpeakerModel().run(listOf(a, queued, p)).single { it.id == "p" }.completed)
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

    /** The frozen model with these errors on these sentences: does the baseline speak P by the deadline and the arm not? */
    private fun frozenRegression(deadlineMs: Long, queued: List<SpeakerModel.Queued>, base: Map<Long, SpeakerModel.Offer>, arm: Map<Long, SpeakerModel.Offer>, errors: Map<String, Long>): Boolean {
        fun keeps(offers: Map<Long, SpeakerModel.Offer>): Boolean {
            val inputs = offers.values.map { it.copy(durationMs = it.durationMs + (errors[it.id] ?: 0)) } + queued.map { it.copy(durationMs = it.durationMs + (errors[it.id] ?: 0)) }
            val p = SpeakerModel().run(inputs).single { it.id == "p" }
            return p.completed && p.endAtMs!! <= deadlineMs
        }
        return keeps(base) && !keeps(arm)
    }

    @Test
    fun `single faults find exactly what the frozen model finds at every millisecond of one sentence's error`() {
        val random = Random(23)
        var found = 0
        repeat(60) {
            val (frames, base, arm) = randomStream(random)
            val queued = List(1 + random.nextInt(3)) { i -> SpeakerModel.Queued(50L * random.nextInt(80) + 7, "n$i", if (random.nextBoolean()) 3 else null, 400L + random.nextInt(800)) }
            val run = PairedEvaluation({ 150 }, listOf(targetOf(5_500)), queued, frames.toLongArray(), 0, heldAfterDone = false, frameSkewMs = 0)
            for (t in frames) run.onFrame(t, { decision(base[t]) }, { decision(arm[t]) })
            run.finish()
            val ids = (base.values + arm.values).map { it.id }.toSet() + queued.map { it.id }
            val frozen = frozenRegression(5_500, queued, base, arm, emptyMap()) ||
                ids.any { id -> (-150L..150L).any { error -> frozenRegression(5_500, queued, base, arm, mapOf(id to error)) } }
            assertEquals(frozen, target in run.result(0, withPairs = false).regressions)
            if (frozen) found++
        }
        println("random streams, frozen model at every millisecond of one error: $found regressions")
        assertTrue("the streams exercise it", found >= 5)
    }

    @Test
    fun `against the exact reference with queued sentences, nothing is invented, and what pairs miss takes three errors`() {
        val random = Random(21)
        var exactRegressions = 0
        var bySingles = 0
        var byPairs = 0
        var takeThree = 0
        repeat(200) {
            val (frames, base, arm) = randomStream(random)
            val queued = List(1 + random.nextInt(3)) { i -> SpeakerModel.Queued(50L * random.nextInt(80) + 7, "n$i", if (random.nextBoolean()) 3 else null, 400L + random.nextInt(800)) }
            val run = PairedEvaluation({ 150 }, listOf(targetOf(5_500)), queued, frames.toLongArray(), 0, heldAfterDone = false, frameSkewMs = 0)
            val reference = PairedBranchRun(frozenKernel, listOf(targetOf(5_500)), queued)
            for (t in frames) {
                run.onFrame(t, { decision(base[t]) }, { decision(arm[t]) })
                reference.onFrame(t, { decision(base[t]) }, { decision(arm[t]) })
            }
            run.finish()
            val exact = reference.finish()
            assertTrue("the reference settles these", !exact.capHit)
            assertTrue("nothing the exact run does not have", (run.result(0).regressions - exact.regressions).isEmpty())
            exactRegressions += exact.regressions.size
            bySingles += run.result(0, withPairs = false).regressions.size
            byPairs += run.result(0).regressions.size
            if (target !in exact.regressions || target in run.result(0).regressions) return@repeat
            // Missed: the frozen model must not show it with two errors, on as fine a grid as is affordable.
            val ids = ((base.values + arm.values).map { it.id }.toSet() + queued.map { it.id }).toList()
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                for (x in -150L..150L step 3) for (y in -150L..150L step 3) {
                    assertTrue("${ids[i]} $x, ${ids[j]} $y", !frozenRegression(5_500, queued, base, arm, mapOf(ids[i] to x, ids[j] to y)))
                }
            }
            takeThree++
        }
        println("random streams with queued sentences: exact regressions $exactRegressions, by single faults $bySingles, and pairs $byPairs, the rest $takeThree")
        assertTrue("the streams exercise it", exactRegressions >= 10)
        assertEquals(exactRegressions, byPairs + takeThree)
    }

    private val heldKernel get() = ClosedLoopSpeakerBranches({ 150 }, heldAfterDone = true, frameSkewMs = 50)

    @Test
    fun `the branch run finds what takes two errors moved together, a sentence and the one queued behind it`() {
        val a = offer(0, "a")
        val p = offer(2_100, "p")
        val base = mapOf(0L to a, 2_100L to p)
        val arm = base + (1_750L to offer(1_750, "x"))
        val queued = listOf(SpeakerModel.Queued(10, "queued", 3, 1_000))
        val reference = PairedBranchRun(frozenKernel, listOf(targetOf(3_500)), queued)
        for (t in listOf(0L, 10L, 1_750L, 2_100L, 3_300L, 3_600L)) reference.onFrame(t, { decision(base[t]) }, { decision(arm[t]) })
        assertEquals(setOf(target), reference.finish().regressions)
    }

    @Test
    fun `the branch run finds what takes one error and part of a frame's lateness`() {
        // The arm's first sentence 140 ms short and the frame at 1 000 seen 20 ms late: R is taken in the arm alone, and its record holds P off.
        val record = event(EventCategory.ROAD_WARNING, "road_warning_control", 1_000)
        fun decide(cooldown: CooldownManager, t: Long, experimental: Boolean): PairedBranchRun.Decision? = when {
            t == 0L -> decision(if (experimental) offer(0, "a", 1_160) else offer(0, "b", 1_025))
            t == 1_000L -> {
                cooldown.recordEvent(record, t)
                PairedBranchRun.Decision(offer(t, "r", 500), emptyList(), onAccepted = { it.confirm(record.id) }, onRefused = { it.revoke(record.id) })
            }
            t == 2_000L && cooldown.filter(listOf(record), t).isNotEmpty() -> decision(offer(t, "p"))
            else -> null
        }
        val reference = PairedBranchRun(heldKernel, listOf(targetOf(3_500)), emptyList())
        for (t in listOf(0L, 1_000L, 2_000L, 3_300L, 3_600L)) reference.onFrame(t, { decide(it, t, false) }, { decide(it, t, true) })
        assertEquals(setOf(target), reference.finish().regressions)
    }

    @Test
    fun `the branch run finds what takes three errors at once`() {
        // The arm withholds P only if all three sentences ran short enough for all three records to stand.
        val controls = List(3) { i -> event(EventCategory.ROAD_WARNING, "road_warning_control$i", i * 2_000L + 901) }
        fun decide(cooldown: CooldownManager, t: Long, experimental: Boolean): PairedBranchRun.Decision? {
            for (i in 0 until 3) {
                val start = i * 2_000L
                if (t == start) return decision(offer(t, "ca$i"))
                if (t == start + 900) return decision(offer(t, "cb$i"))
                if (t == start + 901) {
                    val r = controls[i]
                    cooldown.recordEvent(r, t)
                    return PairedBranchRun.Decision(offer(t, "cr$i", 500), emptyList(), onAccepted = { it.confirm(r.id) }, onRefused = { it.revoke(r.id) })
                }
            }
            if (t != 6_500L) return null
            val all = controls.all { cooldown.filter(listOf(it), t).isEmpty() }
            return if (experimental && all) null else decision(offer(t, "p"))
        }
        val frames = sortedSetOf(6_500L, 7_800L, 8_100L)
        for (i in 0 until 3) {
            val start = i * 2_000L
            frames += start
            frames += start + 901
            for (offset in 850L..1_150L step 5) frames += start + offset
        }
        val reference = PairedBranchRun(heldKernel, listOf(targetOf(8_000)), emptyList())
        for (t in frames) reference.onFrame(t, { decide(it, t, false) }, { decide(it, t, true) })
        val result = reference.finish()
        assertTrue(!result.capHit)
        assertEquals(setOf(target), result.regressions)
    }

    @Test
    fun `a frame seen late by less than the whole of the skew is a fault of its own`() {
        // Both arms offer P at 1 000, over a sentence of their own: the baseline's ends at 1 020, the arm's at 1 040.
        // Seen 30 ms late, the baseline's is over and the arm's is not, so only the baseline's P is taken.
        // Seen on time both are refused, seen a whole δ late both are taken, and neither is a regression.
        val theirs = offer(0, "b", 1_020)
        val ours = offer(0, "a", 1_040)
        val p = offer(1_000, "p", 800)
        val frames = listOf(0L, 1_000L, 1_500L, 2_500L)
        val run = PairedEvaluation({ 0 }, listOf(targetOf(2_400)), emptyList(), frames.toLongArray(), 0, heldAfterDone = false, frameSkewMs = 50)
        for (t in frames) run.onFrame(t, { decision(mapOf(0L to theirs, 1_000L to p)[t]) }, { decision(mapOf(0L to ours, 1_000L to p)[t]) })
        run.finish()
        assertEquals(setOf(target), run.result(0).regressions)
    }

    @Test
    fun `with a frame's skew and a late onDone too, nothing is invented`() {
        val random = Random(24)
        var exactRegressions = 0
        var found = 0
        repeat(100) {
            val (frames, base, arm) = randomStream(random)
            val queued = List(random.nextInt(3)) { i -> SpeakerModel.Queued(50L * random.nextInt(80) + 7, "n$i", 3, 400L + random.nextInt(800)) }
            val run = PairedEvaluation({ 150 }, listOf(targetOf(5_500)), queued, frames.toLongArray(), 0, heldAfterDone = true, frameSkewMs = 50)
            val reference = PairedBranchRun(ClosedLoopSpeakerBranches({ 150 }, heldAfterDone = true, frameSkewMs = 50), listOf(targetOf(5_500)), queued)
            for (t in frames) {
                run.onFrame(t, { decision(base[t]) }, { decision(arm[t]) })
                reference.onFrame(t, { decision(base[t]) }, { decision(arm[t]) })
            }
            run.finish()
            val exact = reference.finish()
            if (exact.capHit) return@repeat
            assertTrue("nothing the exact run does not have", (run.result(0).regressions - exact.regressions).isEmpty())
            exactRegressions += exact.regressions.size
            found += run.result(0).regressions.size
        }
        println("random streams with skew: exact regressions $exactRegressions, found $found")
        assertTrue("the streams exercise it", exactRegressions >= 5)
        // The one left needs four things at once, worked by hand: a queued sentence's error, the error of the sentence
        // ahead of it in the arm, the error of a later sentence, and a frame seen late.
        assertEquals(exactRegressions - 1, found)
    }

    @Test
    fun `a target that changes nothing changes no stage's verdict, and the nominal reading stands alone`() {
        val random = Random(22)
        var apart = 0
        repeat(100) {
            val (frames, base, arm) = randomStream(random)
            val queued = List(random.nextInt(2)) { i -> SpeakerModel.Queued(50L * random.nextInt(80) + 7, "n$i", 3, 400L + random.nextInt(800)) }
            // The cut at the guard's deadline is outside every sentence's interval, so the two have the same realizations.
            val alone = PairedEvaluation({ 150 }, listOf(targetOf(5_500)), queued, frames.toLongArray(), 0, heldAfterDone = false, frameSkewMs = 0)
            val anchored = PairedEvaluation({ 150 }, listOf(targetOf(5_500), PairedEvaluation.Target(guard, 60_000, 0)), queued, frames.toLongArray(), 0, heldAfterDone = false, frameSkewMs = 0)
            val nominalOnly = PairedEvaluation({ 150 }, listOf(targetOf(5_500)), queued, frames.toLongArray(), 0, heldAfterDone = false, frameSkewMs = 0, sweep = false)
            for (run in listOf(alone, anchored, nominalOnly)) {
                for (t in frames) run.onFrame(t, { decision(base[t]) }, { decision(arm[t]) })
                run.finish()
            }
            val a = alone.result(0)
            val b = anchored.result(0)
            assertEquals(a.regressions, b.regressions - guard)
            assertEquals(a.bothLostApart, b.bothLostApart - guard)
            assertEquals(a.sameInputs, b.sameInputs - guard)
            apart += a.bothLostApart.size
            val n = nominalOnly.result(0, withFaults = false)
            val fromSweep = alone.result(0, withFaults = false)
            assertEquals(n.regressions, fromSweep.regressions)
            assertEquals(n.bothLostApart, fromSweep.bothLostApart)
            assertEquals(n.sameInputs, fromSweep.sameInputs)
        }
        println("random streams: both lost apart $apart")
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
