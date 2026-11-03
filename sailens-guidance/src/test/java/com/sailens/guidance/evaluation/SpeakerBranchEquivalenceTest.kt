package com.sailens.guidance.evaluation

import com.sailens.guidance.evaluation.SpeakerModel.Offer
import com.sailens.guidance.evaluation.SpeakerModel.Queued
import com.sailens.guidance.evaluation.SpeakerRobustness.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [ClosedLoopSpeakerBranches] against the two frozen classes on fixed input streams: its frozen
 * configuration is the frozen semantics (E1, E2), and what its two parameters add only ever makes a
 * target harder to keep, while covering every run production can make of the same stream (E3).
 */
class SpeakerBranchEquivalenceTest {

    private val frozen = ClosedLoopSpeakerBranches(heldAfterDone = false, frameSkewMs = 0)

    private fun randomInputs(random: Random, count: Int, withQueued: Boolean = true): List<SpeakerModel.Input> {
        var t = 0L
        return List(count) { i ->
            // Clusters around the interesting gaps, including several inputs in one millisecond.
            t += when (random.nextInt(5)) {
                0 -> 0L
                1 -> random.nextInt(40).toLong()
                2 -> 1_000L + random.nextInt(400)
                3 -> 1_200L + random.nextInt(100)
                else -> random.nextInt(3_000).toLong()
            }
            val duration = 1_000L + random.nextInt(1_500)
            if (withQueued && random.nextInt(8) == 0) {
                Queued(t, "q$i", if (random.nextBoolean()) 2 else null, duration)
            } else {
                Offer(t, "o$i", 1 + random.nextInt(3), t + 500 + random.nextInt(3_000), duration)
            }
        }
    }

    /** Replays [inputs] through the kernel with no error, one branch throughout, as predictions. */
    private fun kernelPredictions(inputs: List<SpeakerModel.Input>): List<SpeakerModel.Prediction> {
        val kernel = ClosedLoopSpeakerBranches(epsilonMs = { 0 }, heldAfterDone = false, frameSkewMs = 0)
        val predictions = LinkedHashMap<String, SpeakerModel.Prediction>()
        fun record(step: ClosedLoopSpeakerBranches.Step) {
            for (o in step.outcomes) {
                when (o) {
                    is ClosedLoopSpeakerBranches.Accepted -> predictions[o.id] = SpeakerModel.Prediction(o.id, accepted = true, startAtMs = o.startAtMs)
                    is ClosedLoopSpeakerBranches.Refused -> predictions[o.id] = SpeakerModel.Prediction(o.id, accepted = false, reason = o.reason)
                    is ClosedLoopSpeakerBranches.Spoken -> predictions[o.id] = predictions.getValue(o.id).copy(endAtMs = o.endAtMs, completed = true)
                    is ClosedLoopSpeakerBranches.Cut -> predictions[o.id] = predictions.getValue(o.id).copy(endAtMs = o.atMs, completed = false)
                    is ClosedLoopSpeakerBranches.HeardAfterCut -> error("no such branch here")
                }
            }
        }
        var speaker = kernel.start()
        for (input in inputs.sortedBy { it.atMs }) {
            speaker = kernel.settle(speaker, input.atMs).single().also(::record).speaker
            speaker = kernel.submit(speaker, input).single().also(::record).speaker
        }
        kernel.settle(speaker, Long.MAX_VALUE).single().also(::record)
        return predictions.values.toList()
    }

    private fun ids(inputs: List<SpeakerModel.Input>) = inputs.map {
        when (it) {
            is Offer -> it.id
            is Queued -> it.id
            is SpeakerModel.EngineReady -> error("no engine input here")
        }
    }

    @Test
    fun `E1 - with no error every prediction equals the frozen model's`() {
        val random = Random(1)
        repeat(1_500) {
            val inputs = randomInputs(random, 2 + random.nextInt(40))
            assertEquals(SpeakerModel().run(inputs).sortedBy { it.id }, kernelPredictions(inputs).sortedBy { it.id })
        }
    }

    @Test
    fun `E1 - an engine that is not ready refuses both kinds of input`() {
        val inputs = listOf(
            Offer(0, "a", 1, 5_000, 1_000),
            SpeakerModel.EngineReady(10, false),
            Offer(20, "b", 3, 5_000, 1_000),
            Queued(30, "c", null, 1_000),
            SpeakerModel.EngineReady(40, true),
            Offer(50, "d", 3, 5_000, 1_000),
        )
        assertEquals(SpeakerModel().run(inputs).sortedBy { it.id }, kernelPredictions(inputs).sortedBy { it.id })
    }

    private fun assertSameAsFrozen(inputs: List<SpeakerModel.Input>, target: String, deadline: Long, epsilon: Long, cap: Int = 64) {
        val expected = SpeakerRobustness(epsilonMs = epsilon, maxBranches = cap).check(inputs, target, deadline)
        val actual = ClosedLoopSpeakerBranches(epsilonMs = { epsilon }, heldAfterDone = false, frameSkewMs = 0)
            .check(inputs, target, deadline, cap)
        assertEquals("target $target of $inputs", expected, actual)
    }

    @Test
    fun `E2 - review round 3's A, B, P`() {
        val abp = listOf(
            Offer(0, "a", 3, 3_000, 1_237),
            Offer(1_250, "b", 3, 4_250, 1_237),
            Offer(2_500, "p", 3, 5_500, 1_237),
        )
        assertSameAsFrozen(abp, "p", 4_237, 150)
        assertSameAsFrozen(abp, "p", 4_237, 0)
        assertEquals(Verdict.LOST, frozen.check(abp, "p", 4_237).verdict)
    }

    @Test
    fun `E2 - two calls of one frame compare once - the third review's counterexample`() {
        val inputs = listOf(
            Offer(0, "a", 3, 5_000, 1_000),
            Offer(1_000, "b", 2, 5_000, 1_000),
            Offer(1_000, "c", 1, 5_000, 3_000),
            Offer(2_500, "p", 1, 8_000, 1_000),
        )
        assertSameAsFrozen(inputs, "p", Long.MAX_VALUE, 150)
        val outcome = frozen.check(inputs, "p")
        assertEquals(Verdict.KEPT, outcome.verdict)
        assertEquals(3_650L, outcome.latestEndMs)
    }

    @Test
    fun `E2 - on random streams every target agrees, deadlines, one millisecond and the branch limit included`() {
        val random = Random(2)
        repeat(1_500) {
            val inputs = randomInputs(random, 3 + random.nextInt(25))
            val target = ids(inputs)[random.nextInt(inputs.size)]
            val at = inputs.first { i -> ids(listOf(i)).single() == target }.atMs
            val deadline = if (random.nextBoolean()) Long.MAX_VALUE else at + 500 + random.nextInt(4_000)
            val epsilon = if (random.nextBoolean()) 150L else 300L
            assertSameAsFrozen(inputs, target, deadline, epsilon, cap = listOf(64, 64, 4, 1)[random.nextInt(4)])
        }
    }

    private val branching = ClosedLoopSpeakerBranches()

    @Test
    fun `E3a - hearing a sentence that was then superseded changes nobody else's verdict`() {
        val random = Random(3)
        val held = ClosedLoopSpeakerBranches(heldAfterDone = true, frameSkewMs = 0)
        repeat(1_500) {
            val inputs = randomInputs(random, 3 + random.nextInt(14), withQueued = false)
            val target = ids(inputs)[random.nextInt(inputs.size)]
            val plain = frozen.check(inputs, target, maxBranches = 10_000)
            val withHeld = held.check(inputs, target, maxBranches = 10_000)
            assertEquals("$target of $inputs", plain, withHeld)
        }
    }

    @Test
    fun `E3b - a frame skew only makes it stricter`() {
        val random = Random(4)
        repeat(1_500) {
            val inputs = randomInputs(random, 3 + random.nextInt(12), withQueued = false)
            val target = ids(inputs)[random.nextInt(inputs.size)]
            val at0 = frozen.check(inputs, target, maxBranches = 100_000)
            val at50 = branching.check(inputs, target, maxBranches = 100_000)
            if (at50.verdict == Verdict.KEPT) {
                assertEquals("$target of $inputs", Verdict.KEPT, at0.verdict)
                assertTrue("$target of $inputs", at50.latestEndMs!! >= at0.latestEndMs!!)
            }
            if (at0.verdict == Verdict.LOST) assertEquals("$target of $inputs", Verdict.LOST, at50.verdict)
        }
    }

    /**
     * What production makes of [offers] under one choice of every sentence's actual end, every frame's
     * distance from its decision and every frame's own run time, by the settlement rules: a frame settles
     * what ended by its time, compares and judges expiry at that time, and a flush cuts what has not
     * ended by the moment the frame reaches it.
     */
    private fun concreteRun(
        offers: List<Offer>,
        endOf: (Offer) -> Long,
        skewOf: (Offer) -> Long,
        runOf: (Offer) -> Long,
    ): Map<String, Long> {
        class Playing(val offer: Offer, val end: Long)
        var playing: Playing? = null
        val heard = HashMap<String, Long>()
        for (o in offers.sortedBy { it.atMs }) {
            val frame = o.atMs + skewOf(o)
            playing?.let { p ->
                if (p.end <= frame) {
                    heard[p.offer.id] = p.end
                    playing = null
                }
            }
            val holder = playing?.takeIf { frame <= it.offer.expiresAtMs }
            if (frame > o.expiresAtMs || (holder != null && o.priority <= holder.offer.priority)) continue
            playing?.let { p -> if (p.end <= frame + runOf(o)) heard[p.offer.id] = p.end }
            playing = Playing(o, endOf(o))
        }
        playing?.let { heard[it.offer.id] = it.end }
        return heard
    }

    @Test
    fun `E3c - a target kept across the branches is spoken in every run production can make`() {
        val random = Random(5)
        var keptChecked = 0
        repeat(600) {
            val offers = randomInputs(random, 3 + random.nextInt(10), withQueued = false).filterIsInstance<Offer>()
            for (target in offers) {
                val outcome = branching.check(offers, target.id, maxBranches = 100_000)
                if (outcome.verdict != Verdict.KEPT) continue
                keptChecked++
                repeat(30) {
                    val ends = offers.associate { o ->
                        o.id to o.atMs + o.durationMs + listOf(-150L, -100L, -1L, 0L, 1L, 50L, 100L, 150L)[random.nextInt(8)]
                    }
                    // Per decision time: the calls of one frame see one frame time.
                    val skews = offers.map { it.atMs }.distinct().associateWith { random.nextInt(51).toLong() }
                    val runs = offers.associate { it.id to random.nextInt(30).toLong() }
                    val run = concreteRun(offers, { ends.getValue(it.id) }, { skews.getValue(it.atMs) }, { runs.getValue(it.id) })
                    val heardAt = run[target.id]
                    assertTrue("${target.id} of $offers: never heard with $ends, $skews, $runs", heardAt != null)
                    assertTrue("${target.id} of $offers: heard at $heardAt, after ${outcome.latestEndMs}", heardAt!! <= outcome.latestEndMs!!)
                }
            }
        }
        assertTrue("the check must have had something to check: $keptChecked", keptChecked > 500)
    }

    @Test
    fun `E3d - a frame just after its decision can accept what the model refused, and lose the next target`() {
        val inputs = listOf(
            Offer(0, "a", 3, 8_000, 1_258),
            Offer(1_107, "b", 3, 8_000, 1_258),
            Offer(1_600, "c", 3, 8_000, 1_258),
        )
        assertEquals(Verdict.KEPT, SpeakerRobustness().verdict(inputs, "c"))
        assertEquals(Verdict.KEPT, frozen.check(inputs, "c").verdict)
        assertEquals(Verdict.LOST, branching.check(inputs, "c").verdict)
    }

    @Test
    fun `E3d - a frame past the whole interval is one branch, and takes the next prompt`() {
        val inputs = listOf(Offer(0, "a", 3, 8_000, 1_258), Offer(1_460, "b", 3, 8_000, 1_258))
        val kernel = branching
        var speaker = kernel.start()
        speaker = kernel.submit(speaker, inputs[0]).single().speaker
        val settled = kernel.settle(speaker, 1_460).single()
        assertEquals(listOf<ClosedLoopSpeakerBranches.Outcome>(ClosedLoopSpeakerBranches.Spoken("a", 1_408)), settled.outcomes)
        assertTrue(kernel.submit(settled.speaker, inputs[1]).single().outcomes.single() is ClosedLoopSpeakerBranches.Accepted)
    }

    @Test
    fun `the second call of a frame compares nothing again`() {
        val kernel = branching
        var speaker = kernel.start()
        speaker = kernel.submit(speaker, Offer(0, "a", 3, 8_000, 1_258)).single().speaker
        val steps = kernel.settle(speaker, 1_107)
        assertEquals(2, steps.size)
        val live = steps.last().speaker
        assertEquals("already compared at this time", 1, kernel.settle(live, 1_107).size)
        assertEquals("a later frame compares again", 2, kernel.settle(live, 1_200).size)
    }

    @Test
    fun `a flush inside the interval has four outcomes for the sentence it supersedes`() {
        val kernel = branching
        var speaker = kernel.start()
        speaker = kernel.submit(speaker, Offer(0, "a", 1, 8_000, 1_258)).single().speaker
        val offer = Offer(1_107, "c", 3, 8_000, 1_258)
        val steps = kernel.settle(speaker, 1_107).flatMap { s -> kernel.submit(s.speaker, offer).map { s.outcomes + it.outcomes to it.speaker } }
        // Over before the frame; on the speaker and heard before the flush; cut; cut and heard later.
        assertEquals(4, steps.size)
        val resultsOfA = steps.map { (outcomes, speakerAfter) ->
            val now = outcomes.filter { it.id == "a" }.map { it::class.simpleName }
            val later = kernel.settle(speakerAfter, 1_200).flatMap { it.outcomes }.filter { it.id == "a" }.map { it::class.simpleName }
            now to later
        }.toSet()
        assertEquals(
            setOf(
                listOf("Spoken") to emptyList<String>(),
                emptyList<String>() to listOf("Spoken"),
                listOf("Cut") to emptyList(),
                listOf("Cut") to listOf("HeardAfterCut"),
            ),
            resultsOfA,
        )
    }

    @Test
    fun `an expiry inside the skew is followed both ways, for the offer and for what holds the speaker`() {
        val kernel = branching
        val lateOffer = Offer(1_000, "o", 1, 1_020, 1_000)
        assertEquals(2, kernel.submit(kernel.start(), lateOffer).size)
        var speaker = kernel.start()
        speaker = kernel.submit(speaker, Offer(0, "a", 3, 1_020, 5_000)).single().speaker
        val blocked = kernel.submit(speaker, Offer(1_000, "b", 1, 9_000, 1_000)).map { it.outcomes.last()::class.simpleName }.toSet()
        assertEquals(setOf("Refused", "Accepted"), blocked)
    }
}
