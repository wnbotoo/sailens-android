package com.sailens.guidance.evaluation

import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.Severity
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.decision.CooldownManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.UUID

class PairedBranchRunTest {
    private val target = StageKey("p", Stage.CRITICAL)
    private val guard = StageKey("guard", Stage.CRITICAL)
    private val frozenKernel get() = ClosedLoopSpeakerBranches({ 150 }, heldAfterDone = false, frameSkewMs = 0)
    private val heldKernel get() = ClosedLoopSpeakerBranches({ 150 }, heldAfterDone = true, frameSkewMs = 50)
    private val noErrorKernel get() = ClosedLoopSpeakerBranches({ 0 }, heldAfterDone = false, frameSkewMs = 0)

    private fun offer(t: Long, id: String, durationMs: Long = 1_000, priority: Int = 3) = SpeakerModel.Offer(t, id, priority, t + 5_000, durationMs)

    private fun decision(offer: SpeakerModel.Offer?, credited: String = "p") =
        offer?.let { PairedBranchRun.Decision(it, if (it.id == credited) listOf(target) else emptyList(), onAccepted = {}, onRefused = {}) }

    private fun targetOf(deadlineMs: Long, appearedAtMs: Long = 0) = PairedBranchRun.Target(target, deadlineMs, appearedAtMs)

    private fun run(
        deadlineMs: Long,
        frames: List<Long>,
        baseline: Map<Long, SpeakerModel.Offer>,
        arm: Map<Long, SpeakerModel.Offer>,
        queued: List<SpeakerModel.Queued> = emptyList(),
        kernel: ClosedLoopSpeakerBranches = frozenKernel,
        appearedAtMs: Long = 0,
    ): PairedBranchRun.Result {
        val run = PairedBranchRun(kernel, listOf(targetOf(deadlineMs, appearedAtMs)), queued)
        for (t in frames) run.onFrame(t, { decision(baseline[t]) }, { decision(arm[t]) })
        return run.finish()
    }

    @Test
    fun `an arm that changes nothing scores no regression, however close the ends fall`() {
        val offers = mapOf(0L to offer(0, "a", 1_010, 2), 1_000L to offer(1_000, "p", 1_000, 2))
        val result = run(2_500, listOf(0L, 1_000L, 2_000L, 3_000L), offers, offers)
        assertEquals(emptySet<StageKey>(), result.regressions)
        assertEquals("A runs long in some realization, in both arms, and nothing else differs", setOf(target), result.sameInputs)
    }

    @Test
    fun `the A, B, P case of R10 is a regression`() {
        // Only the arm says B. If B runs 40 ms long, P meets it still speaking and is refused; the baseline has no B to meet.
        val a = offer(0, "a", 1_000, 1)
        val b = offer(1_010, "b", 1_000, 1)
        val p = offer(2_020, "p", 1_000, 1)
        val result = run(3_600, listOf(0L, 1_010L, 2_020L, 3_200L, 4_000L), mapOf(0L to a, 2_020L to p), mapOf(0L to a, 1_010L to b, 2_020L to p))
        assertEquals(setOf(target), result.regressions)
        assertTrue("a witness says how", result.witnesses.getValue(target).isNotEmpty())
    }

    @Test
    fun `a sentence ending just before the deadline counts by when it ended, not by the top of its interval`() {
        // The baseline's P ends at 1 000, the deadline is 1 100, the interval's top 1 150. The arm never says P.
        val p = offer(0, "p")
        assertEquals(setOf(target), run(1_100, listOf(0L, 2_000L), mapOf(0L to p), emptyMap()).regressions)
    }

    @Test
    fun `a deadline falling on a frame splits what straddles it, and so does one after the last frame`() {
        val p = offer(0, "p")
        assertEquals("on a frame", setOf(target), run(1_100, listOf(0L, 1_100L, 2_000L), mapOf(0L to p), emptyMap(), kernel = heldKernel).regressions)
        assertEquals("after the last frame", setOf(target), run(1_100, listOf(0L), mapOf(0L to p), emptyMap(), kernel = heldKernel).regressions)
    }

    @Test
    fun `one sentence ending in a one-millisecond gap is found`() {
        // When A ends in (900, 901], B is refused at 900 and the arm's Q is taken at 901 and holds P off; the baseline speaks P.
        val a = offer(0, "a")
        val b = offer(900, "b")
        val p = offer(1_100, "p")
        val base = mapOf(0L to a, 900L to b, 1_100L to p)
        val arm = base + (901L to offer(901, "q"))
        assertEquals(setOf(target), run(2_500, listOf(0L, 900L, 901L, 1_100L, 2_300L, 2_600L), base, arm).regressions)
    }

    private fun event(category: EventCategory, key: String, t: Long) = SceneEvent(
        UUID.randomUUID(), t, category, EventPriority.HIGH, "probe", emptyMap(), t + 5_000, key, setOf(key), emptyList(), 1f, Severity.MODERATE, emptyList(), DistanceLevel.MEDIUM, null,
    )

    @Test
    fun `an old common error reaches a stage through a long cooldown`() {
        // Whether A ran long decides whether R leaves a 60 s record; 19 s later the arm acts on that record, and only then.
        val record = event(EventCategory.GROUND_UNRECOGNIZED, "ground_unrecognized", 1_000)
        fun decide(cooldown: CooldownManager, t: Long, experimental: Boolean): PairedBranchRun.Decision? = when {
            t == 0L -> decision(offer(0, "a", 1_010))
            t == 1_000L -> {
                cooldown.recordEvent(record, t)
                PairedBranchRun.Decision(offer(t, "r"), emptyList(), onAccepted = { it.confirm(record.id) }, onRefused = { it.revoke(record.id) })
            }
            t == 20_000L && experimental && cooldown.filter(listOf(record), t).isEmpty() -> decision(offer(t, "q"))
            t == 20_100L -> decision(offer(t, "p"))
            else -> null
        }
        val reference = PairedBranchRun(frozenKernel, listOf(targetOf(23_000)), emptyList())
        for (t in listOf(0L, 1_000L, 20_000L, 20_100L, 22_000L, 24_000L)) reference.onFrame(t, { decide(it, t, false) }, { decide(it, t, true) })
        assertEquals(setOf(target), reference.finish().regressions)
    }

    @Test
    fun `an arm that withholds a prompt is a regression, and nothing tells the run the arms differ`() {
        val p = offer(0, "p")
        assertEquals(setOf(target), run(1_500, listOf(0L, 2_000L), mapOf(0L to p), emptyMap()).regressions)
    }

    @Test
    fun `the same words crediting a stage in one arm only count for that arm`() {
        val p = offer(0, "p")
        val run = PairedBranchRun(frozenKernel, listOf(targetOf(1_500)), emptyList())
        for (t in listOf(0L, 2_000L)) {
            run.onFrame(t, { if (t == 0L) decision(p) else null }, { if (t == 0L) decision(p, credited = "nobody") else null })
        }
        assertEquals(setOf(target), run.finish().regressions)
    }

    @Test
    fun `a stage lost in both arms, with different paths, is told apart from a regression and from an exempt one`() {
        val a = offer(5_000, "a", 1_010, 2)
        val b = offer(0, "b", 500, 1)
        val p = offer(6_000, "p", 1_000, 2)
        val frames = listOf(0L, 5_000L, 6_000L, 7_000L, 8_000L)
        val base = mapOf(5_000L to a, 6_000L to p)
        val arm = mapOf(0L to b, 5_000L to a, 6_000L to p)
        val sinceStart = run(7_500, frames, base, arm)
        assertEquals(emptySet<StageKey>(), sinceStart.regressions)
        assertEquals("the arms differed when the stage appeared at 0", setOf(target), sinceStart.bothLostApart)
        assertEquals(emptySet<StageKey>(), sinceStart.sameInputs)
        assertEquals("by 5 500 they have long been the same again", setOf(target), run(7_500, frames, base, arm, appearedAtMs = 5_500).sameInputs)
    }

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
    fun `a branch back on equal state keeps what it did before, the worst of those merged into it`() {
        // If A ends by 1 000 the record stands and the arm alone says Q at 2 000. The record then dies out and
        // both arms are in equal state long before P's deadline, which neither speaks for. The branch where A ran
        // long had the arms the same throughout, and merges with the one that did not.
        val frames = listOf(0L, 1_000L, 2_000L, 3_000L, 22_000L, 25_000L)
        val record = event(EventCategory.OBSTACLE, "obstacle_PRIMARY_CENTER", 1_000)
        fun verdict(appearedAtMs: Long): PairedBranchRun.Result {
            val run = PairedBranchRun(frozenKernel, listOf(PairedBranchRun.Target(target, 24_000, appearedAtMs)), emptyList())
            for (t in frames) run.onFrame(t, { rejoining(it, t, false, record) }, { rejoining(it, t, true, record) })
            return run.finish()
        }
        assertEquals(setOf(target), verdict(0).bothLostApart)
        assertEquals(emptySet<StageKey>(), verdict(0).sameInputs)
        assertEquals("the arms are the same again from then on", setOf(target), verdict(23_000).sameInputs)
    }

    @Test
    fun `a sentence both arms have ends at one moment in both, even with the arms apart`() {
        // A cooldown record keeps the arms apart throughout. A, the same sentence in both, ends around 1 000 in
        // one realization for both, so P is taken in both or refused in both. Alone, each arm would have both.
        val record = event(EventCategory.GROUND_UNRECOGNIZED, "ground_unrecognized", 0)
        fun decide(cooldown: CooldownManager, t: Long, experimental: Boolean): PairedBranchRun.Decision? {
            if (t == 0L && experimental) cooldown.recordEvent(record, t)
            return when (t) {
                0L -> decision(offer(0, "a", 1_010))
                1_000L -> decision(offer(1_000, "p", priority = 3))
                else -> null
            }
        }
        val run = PairedBranchRun(frozenKernel, listOf(targetOf(2_500)), emptyList())
        for (t in listOf(0L, 1_000L, 2_000L, 3_000L)) run.onFrame(t, { decide(it, t, false) }, { decide(it, t, true) })
        val result = run.finish()
        assertEquals(emptySet<StageKey>(), result.regressions)
        assertEquals(setOf(target), result.bothLostApart)
    }

    @Test
    fun `a queued sentence with its start unknown is not taken to end together with its twin behind another sentence`() {
        // Behind A in the baseline and behind B in the arm, with the same bounds: the same words, but not one moment.
        val queued = SpeakerModel.Queued(100, "n", 3, 500)
        val base = mapOf(0L to offer(0, "a", 1_000), 1_500L to offer(1_500, "p", 500, 2))
        val arm = mapOf(0L to offer(0, "b", 1_000), 1_500L to offer(1_500, "p", 500, 2))
        val result = run(2_400, listOf(0L, 100L, 1_500L, 2_500L), base, arm, listOf(queued))
        assertEquals(setOf(target), result.regressions)
        assertTrue("A short and B long: over by 1 500 in the baseline only", frozenRegression(2_400, listOf(queued), base, arm, mapOf("a" to -150L, "b" to 150L)))
    }

    /** Same bounds behind different sentences: A short and B long has N over by 1 600 in the baseline only. */
    private fun twinsBehindDifferentSentences(frames: List<Long>, appearedAtMs: Long = 0) = run(
        2_600, frames,
        mapOf(0L to offer(0, "a", 1_000), 1_600L to offer(1_600, "p", 500)),
        mapOf(0L to offer(0, "b", 1_000), 1_600L to offer(1_600, "p", 500)),
        listOf(SpeakerModel.Queued(100, "n", 3, 600)),
        heldKernel,
        appearedAtMs,
    )

    @Test
    fun `equal bounds do not make two arms one again while what they hold depends on a past that differed`() {
        assertEquals(setOf(target), twinsBehindDifferentSentences(listOf(0L, 100L, 1_600L, 2_800L)).regressions)
        assertEquals("a frame with nothing on it changes nothing", setOf(target), twinsBehindDifferentSentences(listOf(0L, 100L, 1_160L, 1_600L, 2_800L)).regressions)
        val appearedLater = twinsBehindDifferentSentences(listOf(0L, 100L, 1_160L, 1_300L, 1_600L, 2_800L), appearedAtMs = 1_300)
        assertEquals(setOf(target), appearedLater.regressions)
        assertEquals(emptySet<StageKey>(), appearedLater.sameInputs)
    }

    @Test
    fun `two arms become one again once nothing they hold depends on their past`() {
        // Apart until N is over; then equal, with nothing on the speaker, and A running long costs both arms P in one realization.
        val a = offer(5_000, "a", 1_010)
        val p = offer(6_000, "p")
        val base = mapOf(0L to offer(0, "x", 1_000), 5_000L to a, 6_000L to p)
        val arm = mapOf(0L to offer(0, "y", 1_000), 5_000L to a, 6_000L to p)
        val result = run(7_500, listOf(0L, 100L, 2_500L, 5_000L, 6_000L, 7_000L, 8_000L), base, arm, listOf(SpeakerModel.Queued(100, "n", 3, 600)), appearedAtMs = 4_000)
        assertEquals(emptySet<StageKey>(), result.regressions)
        assertEquals(setOf(target), result.sameInputs)
    }

    private fun obstacleEvent(t: Long, priority: EventPriority) = SceneEvent(
        UUID.randomUUID(), t, EventCategory.OBSTACLE, priority, "probe", emptyMap(), t + 5_000, "obstacle_PRIMARY_CENTER", setOf("obstacle_PRIMARY_CENTER"), emptyList(), 1f, Severity.MODERATE, emptyList(), DistanceLevel.MEDIUM, null,
    )

    @Test
    fun `a cooldown's old summary is kept while a write still open can fold onto it again`() {
        val left = CooldownManager()
        val right = CooldownManager()
        obstacleEvent(0, EventPriority.CRITICAL).let { left.recordEvent(it, 0); left.confirm(it.id) }
        obstacleEvent(0, EventPriority.HIGH).let { right.recordEvent(it, 0); right.confirm(it.id) }
        val revoked = obstacleEvent(4_000, EventPriority.CRITICAL)
        val open = listOf(obstacleEvent(3_000, EventPriority.HIGH), revoked, obstacleEvent(6_000, EventPriority.HIGH), obstacleEvent(9_000, EventPriority.HIGH))
        for (c in listOf(left, right)) open.forEach { c.recordEvent(it, it.timestamp) }
        assertTrue("these two behave differently", liveCooldown(left.canonicalState(), 10_000) != liveCooldown(right.canonicalState(), 10_000))
        for (c in listOf(left, right)) c.revoke(revoked.id)
        val critical = obstacleEvent(10_000, EventPriority.CRITICAL)
        assertEquals(0, left.filter(listOf(critical), 10_000).size)
        assertEquals(1, right.filter(listOf(critical), 10_000).size)
    }

    @Test
    fun `with no write open, a cooldown's old summary can matter no more`() {
        val left = CooldownManager()
        val right = CooldownManager()
        obstacleEvent(0, EventPriority.CRITICAL).let { left.recordEvent(it, 0); left.confirm(it.id) }
        obstacleEvent(0, EventPriority.HIGH).let { right.recordEvent(it, 0); right.confirm(it.id) }
        assertEquals(liveCooldown(left.canonicalState(), 30_000), liveCooldown(right.canonicalState(), 30_000))
    }

    @Test
    fun `a watch stays open while a sentence speaking for its stage is still on the speaker`() {
        // The hazard was last seen at 1 000, the sentence for it runs to 8 000: it counts, however far past that.
        val run = PairedBranchRun(frozenKernel, emptyList(), emptyList(), listOf(PairedBranchRun.Watch(target, 1_000)))
        val p = offer(1_000, "p", 7_000)
        for (t in listOf(1_000L, 2_000L, 7_000L, 9_000L)) run.onFrame(t, { decision(if (t == 1_000L) p else null) }, { decision(if (t == 1_000L) p else null) })
        val result = run.finish()
        assertEquals(setOf(target), result.arm.everywhere)
        assertEquals(setOf(target), result.baseline.everywhere)
        assertEquals(emptySet<StageKey>(), result.creditedAfterClose)
    }

    @Test
    fun `a sentence offered for a stage whose watch has closed is reported`() {
        val run = PairedBranchRun(frozenKernel, emptyList(), emptyList(), listOf(PairedBranchRun.Watch(target, 1_000)))
        val p = offer(3_000, "p", 500)
        for (t in listOf(1_000L, 2_000L, 3_000L, 4_000L)) run.onFrame(t, { decision(if (t == 3_000L) p else null) }, { decision(if (t == 3_000L) p else null) })
        val result = run.finish()
        assertEquals(setOf(target), result.creditedAfterClose)
        assertEquals(emptySet<StageKey>(), result.arm.everywhere)
    }

    private fun assertQueuedRegression(frames: List<Long>, deadlineMs: Long, queued: List<SpeakerModel.Queued>, base: Map<Long, SpeakerModel.Offer>, arm: Map<Long, SpeakerModel.Offer>) {
        assertEquals(setOf(target), run(deadlineMs, frames, base, arm, queued).regressions)
    }

    @Test
    fun `a queued sentence's own error is a fault, in a one-millisecond gap`() {
        val b = offer(900, "b")
        val p = offer(1_100, "p")
        val base = mapOf(900L to b, 1_100L to p)
        val arm = base + (901L to offer(901, "q"))
        assertQueuedRegression(listOf(0L, 900L, 901L, 1_100L, 2_300L, 2_600L), 2_500, listOf(SpeakerModel.Queued(0, "a", 3, 1_000)), base, arm)
        assertQueuedRegression(buildList { add(0L); addAll(850L..1_150L); add(2_300L); add(2_600L) }, 2_500, listOf(SpeakerModel.Queued(0, "a", 3, 1_000)), base, arm)
    }

    @Test
    fun `an error moves what is queued behind the sentence`() {
        // Only A ending in (900, 901] puts the queued sentence's end in (1 900, 1 901]: B refused, the arm's Q taken, P held off.
        val a = offer(0, "a")
        val b = offer(1_900, "b")
        val p = offer(2_100, "p")
        val base = mapOf(0L to a, 1_900L to b, 2_100L to p)
        val arm = base + (1_901L to offer(1_901, "q"))
        val queued = SpeakerModel.Queued(10, "queued", 3, 1_000)
        assertQueuedRegression(listOf(0L, 10L, 1_900L, 1_901L, 2_100L, 3_300L, 3_600L), 3_500, listOf(queued), base, arm)
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
        val offers = mapOf(0L to a, 1_950L to offer(1_950, "p"))
        fun completion(q: List<SpeakerModel.Queued>): Long? {
            val run = PairedBranchRun(noErrorKernel, emptyList(), q, listOf(PairedBranchRun.Watch(target, Long.MAX_VALUE)))
            for (t in frames) run.onFrame(t, { decision(offers[t]) }, { decision(offers[t]) })
            return run.finish().baseline.completionMs(target)
        }
        assertEquals("without a queue, P is taken", 2_950L, completion(emptyList()))
        assertNull("queued behind A, it still speaks at 1 950 and holds P off", completion(listOf(queued)))
        assertTrue(!SpeakerModel().run(listOf(a, queued, offers.getValue(1_950L))).single { it.id == "p" }.completed)
    }

    @Test
    fun `it finds what takes two errors moved together, a sentence and the one queued behind it`() {
        val a = offer(0, "a")
        val p = offer(2_100, "p")
        val base = mapOf(0L to a, 2_100L to p)
        val arm = base + (1_750L to offer(1_750, "x"))
        assertQueuedRegression(listOf(0L, 10L, 1_750L, 2_100L, 3_300L, 3_600L), 3_500, listOf(SpeakerModel.Queued(10, "queued", 3, 1_000)), base, arm)
    }

    @Test
    fun `it finds what takes one error and part of a frame's lateness`() {
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
    fun `it finds what takes three errors at once`() {
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
    fun `a frame seen late by less than the whole of the skew is followed`() {
        // Both arms offer P at 1 000, over a sentence of their own: the baseline's ends at 1 020, the arm's at 1 040.
        // Seen 30 ms late, the baseline's is over and the arm's is not, so only the baseline's P is taken.
        val p = offer(1_000, "p", 800)
        val result = run(
            2_400, listOf(0L, 1_000L, 1_500L, 2_500L),
            mapOf(0L to offer(0, "b", 1_020), 1_000L to p), mapOf(0L to offer(0, "a", 1_040), 1_000L to p),
            kernel = ClosedLoopSpeakerBranches({ 0 }, heldAfterDone = false, frameSkewMs = 50),
        )
        assertEquals(setOf(target), result.regressions)
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

    private fun randomQueued(random: Random) = List(1 + random.nextInt(3)) { i ->
        SpeakerModel.Queued(50L * random.nextInt(80) + 7, "n$i", if (random.nextBoolean()) 3 else null, 400L + random.nextInt(800))
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

    /** Some sentence's error alone shows a regression on the frozen model, at any millisecond of it. */
    private fun frozenOneError(deadlineMs: Long, queued: List<SpeakerModel.Queued>, base: Map<Long, SpeakerModel.Offer>, arm: Map<Long, SpeakerModel.Offer>): Boolean {
        val ids = (base.values + arm.values).map { it.id }.toSet() + queued.map { it.id }
        return frozenRegression(deadlineMs, queued, base, arm, emptyMap()) ||
            ids.any { id -> (-150L..150L).any { error -> frozenRegression(deadlineMs, queued, base, arm, mapOf(id to error)) } }
    }

    /** Some errors show a regression on the frozen model, looking at one error at every millisecond, two on a grid, and many at random. */
    private fun frozenShows(random: Random, deadlineMs: Long, base: Map<Long, SpeakerModel.Offer>, arm: Map<Long, SpeakerModel.Offer>): Boolean {
        if (frozenOneError(deadlineMs, emptyList(), base, arm)) return true
        val ids = (base.values + arm.values).map { it.id }.toSet().toList()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            for (x in -150L..150L step 5) for (y in -150L..150L step 5) {
                if (frozenRegression(deadlineMs, emptyList(), base, arm, mapOf(ids[i] to x, ids[j] to y))) return true
            }
        }
        return (0 until 20_000).any { frozenRegression(deadlineMs, emptyList(), base, arm, ids.associateWith { -150L + random.nextInt(301) }) }
    }

    @Test
    fun `without a queue it charges only what some realization on the frozen model shows`() {
        val random = Random(20)
        var regressions = 0
        var byOneError = 0
        repeat(200) { stream ->
            val (frames, base, arm) = randomStream(random)
            val result = run(5_500, frames, base, arm)
            assertTrue("these settle", !result.capHit)
            if (target !in result.regressions) return@repeat
            regressions++
            if (frozenOneError(5_500, emptyList(), base, arm)) byOneError++
            assertTrue("stream $stream, witness ${result.witnesses[target]}", frozenShows(random, 5_500, base, arm))
        }
        println("random streams, no queue: $regressions regressions, $byOneError of them by one error")
        assertTrue("the streams exercise it", regressions >= 20)
    }

    @Test
    fun `no realization the frozen model can be given shows a regression it does not charge`() {
        val random = Random(23)
        var byOneError = 0
        var byMany = 0
        var charged = 0
        var checkedQueued = 0
        repeat(150) { stream ->
            val (frames, base, plainArm) = randomStream(random)
            // Every third arm says the same as the baseline in other words of the same length, so its queue can match in bounds and not in fact.
            val arm = if (stream % 3 == 2) base.mapValues { (t, o) -> if (o.id == "p" || t >= 3_000) o else o.copy(id = "twin${o.id}") } else plainArm
            val queued = if (stream % 3 == 0) emptyList() else randomQueued(random)
            val result = run(5_500, frames, base, arm, queued)
            assertTrue("these settle", !result.capHit)
            if (target in result.regressions) charged++
            if (queued.isNotEmpty()) checkedQueued++
            val ids = ((base.values + arm.values).map { it.id }.toSet() + queued.map { it.id }).toList()
            if (frozenOneError(5_500, queued, base, arm)) {
                byOneError++
                assertTrue("one error: stream $stream", target in result.regressions)
            }
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                for (x in -150L..150L step 25) for (y in -150L..150L step 25) {
                    if (frozenRegression(5_500, queued, base, arm, mapOf(ids[i] to x, ids[j] to y))) {
                        assertTrue("${ids[i]} $x, ${ids[j]} $y: stream $stream", target in result.regressions)
                    }
                }
            }
            repeat(300) {
                val errors = ids.associateWith { -150L + random.nextInt(301) }
                if (frozenRegression(5_500, queued, base, arm, errors)) {
                    byMany++
                    assertTrue("$errors: stream $stream", target in result.regressions)
                }
            }
        }
        println("random streams, frozen model: charged $charged of 150, by one error $byOneError; random realizations showing one $byMany; with a queue $checkedQueued")
        assertTrue("the streams exercise it", byOneError >= 5 && byMany >= 50 && checkedQueued >= 50)
    }

    @Test
    fun `a watch changes no verdict, and a target that changes nothing changes no other`() {
        val random = Random(22)
        repeat(100) {
            val (frames, base, arm) = randomStream(random)
            val queued = if (it % 2 == 0) emptyList() else randomQueued(random)
            fun verdict(targets: List<PairedBranchRun.Target>, watches: List<PairedBranchRun.Watch>): PairedBranchRun.Result {
                val run = PairedBranchRun(heldKernel, targets, queued, watches)
                for (t in frames) run.onFrame(t, { decision(base[t]) }, { decision(arm[t]) })
                return run.finish()
            }
            val alone = verdict(listOf(targetOf(5_500)), emptyList())
            if (alone.capHit) return@repeat
            for (other in listOf(
                verdict(listOf(targetOf(5_500)), listOf(PairedBranchRun.Watch(target, 5_500))),
                verdict(listOf(targetOf(5_500), PairedBranchRun.Target(guard, 60_000, 0)), emptyList()),
            )) {
                assertEquals(alone.regressions, other.regressions - guard)
                assertEquals(alone.bothLostApart, other.bothLostApart - guard)
                assertEquals(alone.sameInputs, other.sameInputs - guard)
                assertEquals(alone.kept, other.kept - guard)
            }
        }
    }

    private val watch = PairedBranchRun.Watch(target, 10_000)

    private fun watched(frames: List<Long>, base: Map<Long, SpeakerModel.Offer>, arm: Map<Long, SpeakerModel.Offer>, kernel: ClosedLoopSpeakerBranches = frozenKernel): PairedBranchRun.Result {
        val run = PairedBranchRun(kernel, emptyList(), emptyList(), listOf(watch))
        for (t in frames) run.onFrame(t, { decision(base[t]) }, { decision(arm[t]) })
        return run.finish()
    }

    @Test
    fun `a watched stage counts by its latest completion over every realization, and has none if any realization loses it`() {
        val p = offer(0, "p", 1_000)
        val spoken = watched(listOf(0L, 2_000L, 11_000L), mapOf(0L to p), mapOf(0L to p))
        assertEquals("the top of the interval", 1_150L, spoken.arm.completionMs(target))
        assertEquals(1_150L, spoken.baseline.completionMs(target))
        assertEquals(setOf(target), spoken.arm.everywhere)

        // If A runs long P is refused, in that realization: no completion, though others have one.
        val a = offer(0, "a", 1_010)
        val late = offer(1_000, "p", 1_000)
        val lost = watched(listOf(0L, 1_000L, 2_500L, 11_000L), mapOf(0L to a, 1_000L to late), mapOf(0L to a, 1_000L to late))
        assertNull(lost.arm.completionMs(target))
        assertEquals(emptySet<StageKey>(), lost.arm.unresolved)
        assertEquals("each arm by the same rule", lost.baseline.completionMs(target), lost.arm.completionMs(target))
    }

    @Test
    fun `a stage one arm is done with does not part two arms in equal state`() {
        // By 2 000 the baseline has spoken for P everywhere; the arm, which said something else first, has not.
        // From there the arms are in equal state but for that, and a shared sentence must not split them.
        val a = offer(0, "a", 1_010)
        val base = mapOf(0L to offer(0, "p", 1_000), 2_000L to a, 3_000L to offer(3_000, "b", 1_000))
        val arm = mapOf(0L to offer(0, "q", 1_000), 2_000L to a, 3_000L to offer(3_000, "b", 1_000), 9_000L to offer(9_000, "p", 500))
        val result = watched(listOf(0L, 2_000L, 3_000L, 4_500L, 9_000L, 9_800L, 11_000L), base, arm)
        assertEquals(setOf(target), result.baseline.everywhere)
        assertEquals(setOf(target), result.arm.everywhere)
    }

    @Test
    fun `past the limit what was settled stands, and the rest is undeterminable, by a count and not by a clock`() {
        val p = offer(0, "p", 1_000)
        val settled = StageKey("settled", Stage.CRITICAL)
        val creditsSettled = PairedBranchRun.Decision(p, listOf(settled), onAccepted = {}, onRefused = {})
        val frames = (0L..20_000L step 100L).toList()
        val many = frames.filter { it >= 3_000 && it % 500 == 0L }.associateWith { offer(it, "m$it", 495, 3) }
        for (limit in listOf(
            PairedBranchRun(frozenKernel, listOf(PairedBranchRun.Target(settled, 2_000, 0), targetOf(19_000)), emptyList(), maxBranches = 2),
            PairedBranchRun(frozenKernel, listOf(PairedBranchRun.Target(settled, 2_000, 0), targetOf(19_000)), emptyList(), maxBranchFrames = 40),
        )) {
            for (t in frames) {
                limit.onFrame(t, { if (t == 0L) creditsSettled else decision(many[t]) }, { if (t == 0L) creditsSettled else decision(many[t]) })
            }
            val result = limit.finish()
            assertTrue(result.capHit)
            assertEquals(setOf(settled), result.kept)
            assertEquals(setOf(target), result.undeterminable)
        }
    }
}
