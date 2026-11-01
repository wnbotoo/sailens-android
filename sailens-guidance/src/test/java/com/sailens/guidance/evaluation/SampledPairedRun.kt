package com.sailens.guidance.evaluation

import com.sailens.guidance.processor.decision.CooldownManager

/**
 * The paired reading of M5 ([PairedBranchRun]) by fixed samples of the errors instead of following every
 * one: each sample draws one end per sentence inside its interval, half of them at an endpoint, and runs
 * both arms under those draws. The same sentence ends at the same moment in both. A stage is a regression
 * if some sample has the baseline keeping it and the arm not. Sample 0 is the nominal run.
 *
 * The cost is the sample count times two runs, whatever the policies do, so there is no limit to hit. What
 * it gives up is the guarantee: a stage lost only where several sentences fall in narrow ranges at once
 * can be missed. A stage needing one sentence past some point inside its interval is found with
 * probability at least 1 - (3/4)^n, since a quarter of the draws land on each endpoint.
 *
 * @param differs this frame gets a different answer from some cooldown state; on any other, two arms in
 * equal state stay equal, so only one is run.
 * @param differsNear a difference frame within the look-back before this deadline.
 */
internal class SampledPairedRun(
    private val kernel: ClosedLoopSpeakerBranches,
    targets: List<BranchLoadMeasure.Target>,
    private val queued: List<SpeakerModel.Queued>,
    private val differs: (Long) -> Boolean,
    private val differsNear: (Long) -> Boolean,
    samples: Int,
    private val frameSkewMs: Long,
    private val seed: Long = 0x5A11E25L,
) {
    private val creditsOf = HashMap<String, List<StageKey>>()
    private val all = targets.map { it.key }.toSet()
    private val byDeadline = targets.sortedBy { it.deadlineMs }
    private val deadlineOf = targets.associate { it.key to it.deadlineMs }

    /** The lowest sample that showed each. */
    private val regressedIn = HashMap<StageKey, Int>()
    private val bothLost = mutableSetOf<StageKey>()
    private val armLost = mutableSetOf<StageKey>()

    private class Arm(val speaker: ClosedLoopSpeakerBranches.Speaker, val cooldown: CooldownManager, val kept: Set<StageKey>) {
        fun sameAs(other: Arm, now: Long) = speaker == other.speaker && kept == other.kept &&
            liveCooldown(cooldown.canonicalState(), now) == liveCooldown(other.cooldown.canonicalState(), now)
    }

    private inner class Sample(val index: Int) {
        private val ends = HashMap<String, Long>()
        private val queuedInputs = ArrayDeque(queued.sortedBy { it.atMs })
        private var next = 0
        private var base = Arm(kernel.start(), CooldownManager(), emptySet())
        private var arm = base
        private var coupled = true

        private fun hash(id: String, t: Long): Long {
            var h = seed * 31 + index
            h = h * 1_000_003 + id.hashCode()
            h = h * 1_000_003 + t
            h = h xor (h ushr 29)
            h *= -0x4B47D0C5A1B2D3E5L
            return h xor (h ushr 32)
        }

        private fun drawEnd(item: ClosedLoopSpeakerBranches.Item): Long {
            val middle = (item.endLo + item.endHi) / 2
            if (index == 0) return middle
            val half = (item.endHi - item.endLo) / 2
            val h = hash(item.id, 0)
            return when (Math.floorMod(h, 4L)) {
                0L -> middle - half
                1L -> middle + half
                else -> middle - half + Math.floorMod(h ushr 2, 2 * half + 1)
            }
        }

        private fun remember(speaker: ClosedLoopSpeakerBranches.Speaker) {
            for (item in speaker.queue) if (item.id !in ends) ends[item.id] = drawEnd(item)
        }

        private fun settled(a: Arm, t: Long): Arm {
            val steps = kernel.settle(a.speaker, t)
            if (steps.size == 1) return credited(a, steps.single())
            val skew = if (index == 0 || frameSkewMs == 0L) 0 else Math.floorMod(hash("skew", t), frameSkewMs + 1)
            val step = steps.firstOrNull { s ->
                a.speaker.queue.all { item ->
                    val done = s.outcomes.any { it is ClosedLoopSpeakerBranches.Spoken && it.id == item.id }
                    done == (ends.getValue(item.id) <= t + skew)
                }
            } ?: steps.first()
            return credited(a, step)
        }

        private fun credited(a: Arm, step: ClosedLoopSpeakerBranches.Step, cooldown: CooldownManager = a.cooldown): Arm {
            var marks = a.kept
            for (outcome in step.outcomes) {
                if (outcome !is ClosedLoopSpeakerBranches.Spoken) continue
                for (key in creditsOf[outcome.id].orEmpty()) {
                    val deadline = deadlineOf[key] ?: continue
                    if (outcome.endAtMs <= deadline) marks = marks + key
                }
            }
            return Arm(step.speaker, cooldown, marks)
        }

        private fun decided(a: Arm, decider: PairedBranchRun.Decider, t: Long): Arm {
            val cooldown = a.cooldown.copy()
            val decision = decider.decide(cooldown) ?: return Arm(a.speaker, cooldown, a.kept)
            val id = decision.offer.id
            creditsOf.getOrPut(id) { decision.credits }
            val steps = kernel.submit(a.speaker, decision.offer)
            val nominal = steps.firstOrNull { it.speaker.deferred.size == a.speaker.deferred.size } ?: steps.first()
            val step = if (index == 0 || steps.size == 1) nominal else steps[Math.floorMod(hash(id, t), steps.size.toLong()).toInt()]
            remember(step.speaker)
            val accepted = step.outcomes.any { it is ClosedLoopSpeakerBranches.Accepted && it.id == id }
            if (accepted) decision.onAccepted(cooldown) else decision.onRefused(cooldown)
            return credited(a, step, cooldown)
        }

        private fun withQueued(a: Arm, t: Long, inputs: List<SpeakerModel.Queued>): Arm {
            var now = a
            for (q in inputs) {
                now = credited(now, kernel.submit(now.speaker, q).single())
                remember(now.speaker)
            }
            return now
        }

        fun onFrame(t: Long, baseline: PairedBranchRun.Decider, experimental: PairedBranchRun.Decider) {
            val inputs = mutableListOf<SpeakerModel.Queued>()
            while (queuedInputs.firstOrNull()?.let { it.atMs <= t } == true) inputs += queuedInputs.removeFirst()
            val runOne = coupled && !differs(t)
            base = decided(withQueued(settled(base, t), t, inputs), baseline, t)
            if (runOne) {
                arm = base
            } else {
                arm = decided(withQueued(settled(arm, t), t, inputs), experimental, t)
                coupled = arm.sameAs(base, t)
            }
            closeUpTo(t)
        }

        /** Deadlines passed: score them and drop their marks, so old marks cannot keep two arms apart. */
        fun closeUpTo(t: Long) {
            val closed = mutableSetOf<StageKey>()
            while (next < byDeadline.size && byDeadline[next].deadlineMs < t) {
                val key = byDeadline[next++].key
                closed += key
                if (key in arm.kept) continue
                armLost += key
                if (key in base.kept) regressedIn.merge(key, index, ::minOf) else bothLost += key
            }
            if (closed.isEmpty()) return
            base = Arm(base.speaker, base.cooldown, base.kept - closed)
            arm = if (coupled) base else Arm(arm.speaker, arm.cooldown, arm.kept - closed)
        }

        fun finish() {
            val last = Long.MAX_VALUE
            base = settled(base, last)
            arm = if (coupled) base else settled(arm, last)
            closeUpTo(last)
        }
    }

    private val runs = List(samples + 1) { Sample(it) }
    private var frames = 0

    fun onFrame(t: Long, baseline: PairedBranchRun.Decider, arm: PairedBranchRun.Decider) {
        frames++
        for (sample in runs) sample.onFrame(t, baseline, arm)
    }

    /** The result had only the first [samples] been drawn. */
    fun result(samples: Int): PairedBranchRun.Result {
        val regressions = regressedIn.filterValues { it <= samples }.keys
        val lost = bothLost - regressions
        return PairedBranchRun.Result(
            maxBranches = 1,
            capHit = false,
            framesInWindow = 0,
            frames = frames,
            kept = all - armLost,
            regressions = regressions,
            bothLostNearDifference = lost.filterTo(mutableSetOf()) { differsNear(deadlineOf.getValue(it)) },
            sameInputs = lost.filterNotTo(mutableSetOf()) { differsNear(deadlineOf.getValue(it)) },
            armLostAnyBranch = armLost,
            undeterminable = emptySet(),
        )
    }

    fun finish() = runs.forEach { it.finish() }
}
