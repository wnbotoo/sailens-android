package com.sailens.guidance.evaluation

import com.sailens.guidance.processor.decision.CooldownManager

/**
 * The paired reading of M5: both arms, each deciding for itself on every frame, run under one realization
 * of the per-sentence errors. The same sentence (same frame, same words) ends at the same moment in both.
 * A stage is a regression if some realization has the baseline speaking it by its deadline and the arm
 * not. An arm that says exactly what the baseline says cannot score one, and that is observed, not assumed:
 * nothing here is told whether the arms differ.
 *
 * Which realizations:
 * - **Nominal.** No error.
 * - **Every single fault.** One sentence ending in each gap of its interval, the others nominal. A gap
 *   runs between two moments something compares against: a frame or a stage's deadline. Inside a gap the
 *   sentence's exact end changes nothing, so this covers everything one sentence's error can do, however
 *   narrow its range. Also one frame seen δ late, and each other outcome of one offer. Each starts from
 *   the nominal path and ends when both arms are provably back on it ([ArmPath.sameAs]), so the count
 *   cannot pile up over a session.
 * - **Every pair of faults**, where they can interact: a second fault starts only on a path the first has not
 *   yet left, since a path back on the nominal one has forgotten the first. Counted apart.
 * - **Fixed samples**, for more faults at once: every sentence ending in a gap drawn uniformly, so a
 *   narrow gap is as likely as a wide one. These carry no guarantee.
 *
 * A fault is spent once its frame has run: from then on a path's future is its state alone. So paths in equal
 * state merge, whatever brought them there, and only the worst of their [Path.sameSince] is kept. Pairs stop
 * starting past [maxPathsForPairs], and [pairsCut] says so: the pairs are then not every pair.
 *
 * Provably back on the path means equal speakers, equal marks, and equal cooldowns up to what [liveCooldown]
 * shows cannot affect a later decision. An arm with more state (a delivery protocol) has to add it there.
 *
 * @param epsilonMs half the width of a sentence's end interval: 150 ms, or 300 ms for a fallback wording.
 * @param frames every frame time of the run, sorted.
 */
internal class PairedEvaluation(
    private val epsilonMs: (String) -> Long,
    targets: List<Target>,
    private val queued: List<SpeakerModel.Queued>,
    private val frames: LongArray,
    samples: Int,
    heldAfterDone: Boolean,
    private val frameSkewMs: Long,
    private val sweep: Boolean = true,
    private val pairing: Boolean = sweep,
    private val maxPathsForPairs: Int = 4_000,
    private val seed: Long = 0x5A11E25L,
) {
    /** @param appearedAtMs when the stage first appeared on the device, before anything could be offered for it. */
    class Target(val key: StageKey, val deadlineMs: Long, val appearedAtMs: Long)

    private val kernel = ClosedLoopSpeakerBranches({ 0 }, heldAfterDone, frameSkewMs)
    private val byDeadline = targets.sortedBy { it.deadlineMs }
    private val all = targets.map { it.key }.toSet()
    private val deadlineOf = targets.associate { it.key to it.deadlineMs }
    private val deadlines = byDeadline.map { it.deadlineMs }.distinct().toLongArray()

    /** Which stages each sentence speaks for, by arm: the two arms may credit the same words differently. */
    private val credits = listOf(HashMap<String, List<StageKey>>(), HashMap<String, List<StageKey>>())

    /** One realization's verdicts: what it lost, and whether the arms had run the same since before the stage appeared. */
    private class Verdicts {
        val regressions = mutableSetOf<StageKey>()
        val bothLostSame = mutableSetOf<StageKey>()
        val bothLostApart = mutableSetOf<StageKey>()
    }

    private class ArmPath(val speaker: ClosedLoopSpeakerBranches.Speaker, val cooldown: CooldownManager, val kept: Map<StageKey, Long>) {
        fun sameAs(other: ArmPath, now: Long) = speaker == other.speaker && kept.keys == other.kept.keys &&
            liveCooldown(cooldown.canonicalState(), now) == liveCooldown(other.cooldown.canonicalState(), now)
    }

    private sealed interface Realization {
        /** Where this sentence ends, given its nominal end; null keeps it nominal. */
        fun endOf(id: String, nominalEnd: Long): Long? = null

        /** How much later than the decision this frame compares. */
        fun skewAt(t: Long): Long = 0

        /** Which outcome of this offer, among [size]; null keeps the nominal one. */
        fun stepOf(id: String, t: Long, size: Int): Int? = null
    }

    private data object Nominal : Realization

    private data class EndFault(val id: String, val end: Long) : Realization {
        override fun endOf(id: String, nominalEnd: Long) = end.takeIf { id == this.id }
    }

    private data class TwoFaults(val first: Realization, val second: Realization) : Realization {
        override fun endOf(id: String, nominalEnd: Long) = second.endOf(id, nominalEnd) ?: first.endOf(id, nominalEnd)
        override fun skewAt(t: Long) = maxOf(first.skewAt(t), second.skewAt(t))
        override fun stepOf(id: String, t: Long, size: Int) = second.stepOf(id, t, size) ?: first.stepOf(id, t, size)
    }

    private data class SkewFault(val t: Long, val skew: Long) : Realization {
        override fun skewAt(t: Long) = if (t == this.t) skew else 0
    }

    private data class StepFault(val id: String, val t: Long, val step: Int) : Realization {
        override fun stepOf(id: String, t: Long, size: Int) = step.takeIf { id == this.id && t == this.t }
    }

    private inner class Sampled(private val index: Int) : Realization {
        private fun hash(id: String, t: Long): Long {
            var h = seed * 31 + index
            h = h * 1_000_003 + id.hashCode()
            h = h * 1_000_003 + t
            h = h xor (h ushr 29)
            h *= -0x4B47D0C5A1B2D3E5L
            return h xor (h ushr 32)
        }

        override fun endOf(id: String, nominalEnd: Long): Long {
            val gaps = endsAcrossGaps(nominalEnd, epsilonMs(id))
            return gaps[Math.floorMod(hash(id, 0), gaps.size.toLong()).toInt()]
        }

        override fun skewAt(t: Long) = if (frameSkewMs == 0L) 0 else Math.floorMod(hash("skew", t), frameSkewMs + 1)

        override fun stepOf(id: String, t: Long, size: Int) = Math.floorMod(hash(id, t), size.toLong()).toInt()
    }

    /** One end in each gap of the interval around [nominalEnd]: its upper limit, every frame and every deadline in it. */
    private fun endsAcrossGaps(nominalEnd: Long, epsilon: Long): List<Long> {
        val lo = nominalEnd - epsilon
        val hi = nominalEnd + epsilon
        val cuts = sortedSetOf(hi)
        var i = frames.binarySearch(lo).let { if (it < 0) -it - 1 else it }
        while (i < frames.size && frames[i] < hi) cuts += frames[i++]
        var d = deadlines.binarySearch(lo).let { if (it < 0) -it - 1 else it }
        while (d < deadlines.size && deadlines[d] < hi) {
            cuts += deadlines[d]
            cuts += deadlines[d] + 1
            d++
        }
        return cuts.toList()
    }

    /** Both arms under one realization. */
    private inner class Path(
        val realization: Realization,
        private val verdicts: Verdicts,
        private var base: ArmPath,
        private var arm: ArmPath,
        private var nextQueued: Int,
        private var nextDeadline: Int,
        /** The two arms have had equal state and equal inputs since this time; null while they differ. */
        private var sameSince: Long?,
    ) {
        /** What the frame offered that could be a fault of its own: sentences taken, frames straddled, offers with other outcomes. */
        val taken = mutableMapOf<String, Long>()
        var straddled = false
        val otherSteps = mutableMapOf<String, Int>()

        private fun settled(a: ArmPath, side: Int, t: Long): ArmPath {
            val steps = kernel.settle(a.speaker, t)
            if (steps.size == 1) return credited(a, side, steps.single())
            straddled = true
            val skew = realization.skewAt(t)
            val step = steps.firstOrNull { s ->
                a.speaker.queue.all { item ->
                    val done = s.outcomes.any { it is ClosedLoopSpeakerBranches.Spoken && it.id == item.id }
                    done == (item.endHi <= t + skew)
                }
            } ?: steps.first()
            return credited(a, side, step)
        }

        private fun credited(a: ArmPath, side: Int, step: ClosedLoopSpeakerBranches.Step, cooldown: CooldownManager = a.cooldown): ArmPath {
            var marks = a.kept
            for (outcome in step.outcomes) {
                if (outcome !is ClosedLoopSpeakerBranches.Spoken) continue
                for (key in credits[side][outcome.id].orEmpty()) {
                    val deadline = deadlineOf[key] ?: continue
                    if (key !in marks && outcome.endAtMs <= deadline) marks = marks + (key to outcome.endAtMs)
                }
            }
            return ArmPath(step.speaker, cooldown, marks)
        }

        private fun decided(a: ArmPath, side: Int, decider: PairedBranchRun.Decider, t: Long): Pair<ArmPath, SpeakerModel.Offer?> {
            val cooldown = a.cooldown.copy()
            val decision = decider.decide(cooldown) ?: return ArmPath(a.speaker, cooldown, a.kept) to null
            val nominalOffer = decision.offer
            val id = nominalOffer.id
            val known = credits[side].getOrPut(id) { decision.credits }
            check(known == decision.credits) { "$id speaks for different stages in one arm" }
            val nominalEnd = nominalOffer.atMs + nominalOffer.durationMs
            val offer = nominalOffer.copy(durationMs = (realization.endOf(id, nominalEnd) ?: nominalEnd) - nominalOffer.atMs)
            val steps = kernel.submit(a.speaker, offer)
            val nominal = steps.indexOfFirst { it.speaker.deferred.size == a.speaker.deferred.size }.coerceAtLeast(0)
            if (steps.size > 1) otherSteps[id] = maxOf(otherSteps[id] ?: 0, steps.size)
            val step = steps[realization.stepOf(id, t, steps.size)?.takeIf { it < steps.size } ?: nominal]
            val accepted = step.outcomes.any { it is ClosedLoopSpeakerBranches.Accepted && it.id == id }
            if (accepted) {
                taken[id] = nominalEnd
                decision.onAccepted(cooldown)
            } else {
                decision.onRefused(cooldown)
            }
            return credited(a, side, step, cooldown) to nominalOffer
        }

        private fun withQueued(a: ArmPath, side: Int, inputs: List<SpeakerModel.Queued>): ArmPath {
            var now = a
            for (q in inputs) {
                val nominalEnd = q.atMs + q.durationMs
                val at = q.copy(durationMs = (realization.endOf(q.id, nominalEnd) ?: nominalEnd) - q.atMs)
                now = credited(now, side, kernel.submit(now.speaker, at).single())
            }
            return now
        }

        fun onFrame(t: Long, baseline: PairedBranchRun.Decider, experimental: PairedBranchRun.Decider) {
            taken.clear()
            straddled = false
            otherSteps.clear()
            val first = nextQueued
            while (nextQueued < sortedQueued.size && sortedQueued[nextQueued].atMs <= t) nextQueued++
            val inputs = sortedQueued.subList(first, nextQueued)
            val (b, baseOffer) = decided(withQueued(settled(base, 0, t), 0, inputs), 0, baseline, t)
            val (a, armOffer) = decided(withQueued(settled(arm, 1, t), 1, inputs), 1, experimental, t)
            base = b
            arm = a
            sameSince = if (baseOffer == armOffer && a.sameAs(b, t)) sameSince ?: t else null
            close(t)
        }

        /** Deadlines passed: score them and drop their marks, so old marks cannot keep two paths apart. */
        private fun close(t: Long) {
            val closed = mutableSetOf<StageKey>()
            while (nextDeadline < byDeadline.size && byDeadline[nextDeadline].deadlineMs < t) {
                val target = byDeadline[nextDeadline++]
                closed += target.key
                if (target.key in arm.kept) continue
                when {
                    target.key in base.kept -> verdicts.regressions += target.key
                    sameSince?.let { it <= target.appearedAtMs } == true -> verdicts.bothLostSame += target.key
                    else -> verdicts.bothLostApart += target.key
                }
            }
            if (closed.isEmpty()) return
            if (realization === Nominal) closed.forEach { key -> base.kept[key]?.let { nominalBaseKept[key] = it } }
            base = ArmPath(base.speaker, base.cooldown, base.kept - closed)
            arm = ArmPath(arm.speaker, arm.cooldown, arm.kept - closed)
        }

        fun finish() {
            base = credited(base, 0, kernel.settle(base.speaker, Long.MAX_VALUE).last())
            arm = credited(arm, 1, kernel.settle(arm.speaker, Long.MAX_VALUE).last())
            close(Long.MAX_VALUE)
        }

        fun fork(realization: Realization, verdicts: Verdicts) = Path(realization, verdicts, base, arm, nextQueued, nextDeadline, sameSince)

        /** Equal keys have equal futures. */
        fun stateKey(t: Long): List<Any> = listOf(
            nextQueued, nextDeadline,
            base.speaker, base.kept.keys, liveCooldown(base.cooldown.canonicalState(), t),
            arm.speaker, arm.kept.keys, liveCooldown(arm.cooldown.canonicalState(), t),
        )

        fun absorb(other: Path) {
            val mine = sameSince
            val theirs = other.sameSince
            sameSince = if (mine == null || theirs == null) null else maxOf(mine, theirs)
        }
    }

    private val sortedQueued = queued.sortedBy { it.atMs }
    private val startPath = { r: Realization, v: Verdicts ->
        Path(r, v, ArmPath(kernel.start(), CooldownManager(), emptyMap()), ArmPath(kernel.start(), CooldownManager(), emptyMap()), 0, 0, Long.MIN_VALUE)
    }

    private val nominalVerdicts = Verdicts()
    private val sweepVerdicts = Verdicts()
    private val pairVerdicts = Verdicts()
    private val sampleVerdicts = List(samples) { Verdicts() }
    private val nominal = startPath(Nominal, nominalVerdicts)
    private val sampled = List(samples) { startPath(Sampled(it + 1), sampleVerdicts[it]) }
    private var faults = mutableListOf<Path>()

    private val nominalBaseKept = HashMap<StageKey, Long>()

    private var frameCount = 0
    var faultsRun = 0
        private set
    var pairsRun = 0
        private set
    var pairsCut = false
        private set
    var mostFaultsAtOnce = 0
        private set

    fun onFrame(t: Long, baseline: PairedBranchRun.Decider, experimental: PairedBranchRun.Decider) {
        frameCount++
        for (path in sampled) path.onFrame(t, baseline, experimental)
        val before = nominal.fork(Nominal, nominalVerdicts)
        nominal.onFrame(t, baseline, experimental)
        if (sweep) {
            val singles = mutableListOf<Path>()
            val pairs = mutableListOf<Path>()
            /** A second fault on [path], which has just run this frame from [start]. */
            fun pairsOn(path: Path, start: Path) {
                if (!pairing) return
                if (faults.size + pairs.size > maxPathsForPairs) {
                    pairsCut = true
                    return
                }
                for (second in faultsOf(path, t)) {
                    if (second == path.realization) continue
                    pairsRun++
                    pairs += start.fork(TwoFaults(path.realization, second), pairVerdicts).also { it.onFrame(t, baseline, experimental) }
                }
            }
            for (path in faults) {
                val start = path.fork(path.realization, sweepVerdicts)
                path.onFrame(t, baseline, experimental)
                if (path.realization !is TwoFaults) pairsOn(path, start)
            }
            for (realization in faultsOf(nominal, t)) {
                faultsRun++
                val path = before.fork(realization, sweepVerdicts).also { it.onFrame(t, baseline, experimental) }
                singles += path
                pairsOn(path, before)
            }
            val nominalKey = nominal.stateKey(t)
            val merged = LinkedHashMap<List<Any>, Path>()
            for (path in faults.filter { it.realization !is TwoFaults } + singles + faults.filter { it.realization is TwoFaults } + pairs) {
                val key = path.stateKey(t)
                if (key == nominalKey) continue
                val held = merged[key]
                if (held == null) merged[key] = path else held.absorb(path)
            }
            faults = merged.values.toMutableList()
            mostFaultsAtOnce = maxOf(mostFaultsAtOnce, faults.size)
        }
    }

    /** What the frame [path] just ran offers as a fault of its own. */
    private fun faultsOf(path: Path, t: Long): List<Realization> = buildList {
        for ((id, nominalEnd) in path.taken) {
            for (end in endsAcrossGaps(nominalEnd, epsilonMs(id))) add(EndFault(id, end))
        }
        if (frameSkewMs > 0 && path.straddled) add(SkewFault(t, frameSkewMs))
        for ((id, size) in path.otherSteps) for (step in 0 until size) add(StepFault(id, t, step))
    }

    fun finish() {
        nominal.finish()
        sampled.forEach { it.finish() }
        faults.forEach { it.finish() }
        faults.clear()
    }

    /** When the baseline spoke each stage by its deadline, on the nominal path. */
    fun nominalBaseKept(): Map<StageKey, Long> = nominalBaseKept

    /** The verdict over the nominal path, the first [samples] samples and, if asked, every single fault and pair. */
    fun result(samples: Int, withFaults: Boolean = sweep, withPairs: Boolean = withFaults && pairing): PairedBranchRun.Result {
        val used = buildList {
            add(nominalVerdicts)
            addAll(sampleVerdicts.take(samples))
            if (withFaults) add(sweepVerdicts)
            if (withPairs) add(pairVerdicts)
        }
        val regressions = used.flatMapTo(mutableSetOf()) { it.regressions }
        val apart = used.flatMapTo(mutableSetOf()) { it.bothLostApart } - regressions
        val same = used.flatMapTo(mutableSetOf()) { it.bothLostSame } - regressions - apart
        return PairedBranchRun.Result(
            maxBranches = mostFaultsAtOnce,
            capHit = false,
            kept = all - regressions - apart - same,
            regressions = regressions,
            bothLostApart = apart,
            sameInputs = same,
            armLostSomewhere = regressions + apart + same,
            undeterminable = emptySet(),
        )
    }
}
