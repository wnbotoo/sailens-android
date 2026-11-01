package com.sailens.guidance.evaluation

import com.sailens.guidance.processor.decision.CooldownManager

/**
 * A candidate reading of M5 that compares like with like: both arms run under the same realization of
 * the per-sentence errors, so a stage is a regression only where some realization has the baseline
 * keeping it and the experimental arm not. An arm that changes nothing cannot score one.
 *
 * Each joint branch holds both arms. The same sentence (same frame, same words) ends at the same moment
 * in both, so a straddled end splits both together; a sentence only one arm has splits that arm alone.
 * Two arms in equal state are *coupled*. A coupled branch can part only on a frame where the two
 * policies differ, so away from those frames ([inWindow]) it follows the nominal path alone and coupled
 * branches collapse to one: that is what keeps the count down, not a tighter limit.
 *
 * What collapsing assumes: a frame outside [inWindow] gives both arms the same answer from *any*
 * cooldown state, not just the nominal one. A caller's difference frames have to mean that.
 *
 * @param inWindow a frame within the look-back before a difference frame, where branches open.
 * @param differsNear a difference frame within the look-back before this deadline.
 * Over a whole session the joint count still grows without bound where the policies differ often, so
 * [LocalPairedRun] uses this one stage at a time, from a nominal start shortly before its deadline.
 *
 * @param liveCooldown what of a cooldown state can still affect a decision at the time given.
 */
internal class PairedBranchRun(
    private val kernel: ClosedLoopSpeakerBranches,
    targets: List<BranchLoadMeasure.Target>,
    queued: List<SpeakerModel.Queued>,
    private val inWindow: (Long) -> Boolean,
    private val differsNear: (Long) -> Boolean,
    private val liveCooldown: (CooldownManager.Canonical, Long) -> CooldownManager.Canonical = { state, _ -> state },
    private val maxBranches: Int = 64,
    /** Follow each arm's nominal path alone and close no stage: the path a stage's own run starts from. */
    private val nominalOnly: Boolean = false,
    private val creditsOf: HashMap<String, List<StageKey>> = HashMap(),
    private val nominalEnd: HashMap<String, Long> = HashMap(),
) {
    class Decision(val offer: SpeakerModel.Offer, val credits: List<StageKey>, val onAccepted: (CooldownManager) -> Unit, val onRefused: (CooldownManager) -> Unit)

    /** One arm's answer for this frame from this cooldown; it may write to the cooldown it is given. */
    fun interface Decider {
        fun decide(cooldown: CooldownManager): Decision?
    }

    class Result(
        val maxBranches: Int,
        val capHit: Boolean,
        val framesInWindow: Int,
        val frames: Int,
        /** Spoken in time in every branch. */
        val kept: Set<StageKey>,
        /** Some branch has the baseline keeping the stage and the arm not. */
        val regressions: Set<StageKey>,
        /** Not kept by either arm in the same branch, with the policies differing near the deadline. */
        val bothLostNearDifference: Set<StageKey>,
        /** Not kept by either arm, and no policy difference near the deadline. */
        val sameInputs: Set<StageKey>,
        /** Not kept in some branch, whatever the baseline did: what today's every-branch rule looks at. */
        val armLostAnyBranch: Set<StageKey>,
        val undeterminable: Set<StageKey>,
    )

    private inner class Arm(val speaker: ClosedLoopSpeakerBranches.Speaker, val cooldown: CooldownManager, val kept: Map<StageKey, Long>) {
        private val identity = Triple(speaker, liveCooldown(cooldown.canonicalState(), now), kept)
        override fun equals(other: Any?) = other is Arm && identity == other.identity
        override fun hashCode() = identity.hashCode()

        fun apply(step: ClosedLoopSpeakerBranches.Step, cooldown: CooldownManager = this.cooldown): Arm {
            var marks = kept
            for (outcome in step.outcomes) {
                if (outcome !is ClosedLoopSpeakerBranches.Spoken) continue
                for (key in creditsOf[outcome.id].orEmpty()) {
                    val target = pending[key] ?: continue
                    if (key !in marks && outcome.endAtMs <= target.deadlineMs) marks = marks + (key to outcome.endAtMs)
                }
            }
            return Arm(step.speaker, cooldown, marks)
        }

        fun without(closed: Set<StageKey>) = Arm(speaker, cooldown, kept - closed)
    }

    /** [deviated] holds the sentences off their nominal outcome and since when; it is no part of the state. */
    private inner class Joint(val base: Arm, val arm: Arm, val deviated: Map<String, Long>) {
        val coupled: Boolean get() = base == arm
        override fun equals(other: Any?) = other is Joint && base == other.base && arm == other.arm
        override fun hashCode() = 31 * base.hashCode() + arm.hashCode()
    }

    private var now = 0L
    private val pending = targets.associateBy { it.key }.toMutableMap()
    private val all = pending.keys.toSet()
    private val pendingQueued = ArrayDeque(queued.sortedBy { it.atMs })
    private var branches = listOf(Joint(Arm(kernel.start(), CooldownManager(), emptyMap()), Arm(kernel.start(), CooldownManager(), emptyMap()), emptyMap()))

    private var capHit = false
    private var highest = 1
    private var frames = 0
    private var framesInWindow = 0
    private val kept = mutableSetOf<StageKey>()
    private val regressions = mutableSetOf<StageKey>()
    private val bothLost = mutableSetOf<StageKey>()
    private val sameInputs = mutableSetOf<StageKey>()
    private val armLost = mutableSetOf<StageKey>()

    fun onFrame(t: Long, baseline: Decider, arm: Decider) {
        if (capHit) return
        now = t
        frames++
        val window = inWindow(t)
        if (window) framesInWindow++

        var settled = branches.flatMap { settle(it, t, window) }
        while (pendingQueued.firstOrNull()?.let { it.atMs <= t } == true) {
            val q = pendingQueued.removeFirst()
            settled = settled.map { j ->
                val base = j.base.apply(kernel.submit(j.base.speaker, q).single())
                val experimental = j.arm.apply(kernel.submit(j.arm.speaker, q).single())
                remember(base.speaker)
                remember(experimental.speaker)
                Joint(base, experimental, j.deviated)
            }
        }
        val next = LinkedHashMap<Joint, Joint>()
        for (joint in settled) {
            val onlyNominal = nominalOnly || (joint.coupled && !window)
            val bases = decided(joint.base, baseline, t)
            val arms = decided(joint.arm, arm, t)
            val pairs = if (joint.coupled && bases.size == arms.size && bases.firstOrNull()?.second == arms.firstOrNull()?.second) {
                bases.zip(arms)
            } else {
                bases.flatMap { b -> arms.map { a -> b to a } }
            }
            val chosen = if (onlyNominal) listOf(pairs.first()) else pairs
            for ((b, a) in chosen) {
                val deviated = joint.deviated.toMutableMap()
                listOfNotNull(b.third, a.third).forEach { deviated.putIfAbsent(it, t) }
                val j = Joint(b.first, a.first, deviated)
                val held = next[j]
                if (held == null || deviated.size < held.deviated.size) {
                    next.remove(j)
                    next[j] = j
                }
            }
        }
        branches = if (window) next.keys.toList() else collapse(next.keys.toList())
        highest = maxOf(highest, branches.size)
        if (branches.size > maxBranches) {
            capHit = true
            return
        }
        resolve(t)
    }

    private fun collapse(joints: List<Joint>): List<Joint> {
        val (coupled, apart) = joints.partition { it.coupled }
        return listOfNotNull(coupled.minByOrNull { it.deviated.size }) + apart
    }

    private fun settle(joint: Joint, t: Long, window: Boolean): List<Joint> {
        val baseSteps = kernel.settle(joint.base.speaker, t)
        if (nominalOnly) {
            val base = nominalOf(baseSteps, joint.base.speaker, t)
            val arm = nominalOf(kernel.settle(joint.arm.speaker, t), joint.arm.speaker, t)
            return listOf(Joint(joint.base.apply(base), joint.arm.apply(arm), joint.deviated))
        }
        if (joint.coupled) {
            val steps = if (window) baseSteps else listOf(nominalOf(baseSteps, joint.base.speaker, t))
            return steps.map { s ->
                Joint(joint.base.apply(s), joint.arm.apply(s), offNominal(s, joint.base.speaker, t).associateWith { t } + joint.deviated)
            }
        }
        val armSteps = kernel.settle(joint.arm.speaker, t)
        val baseIds = joint.base.speaker.queue.map { it.id }.toSet()
        val armIds = joint.arm.speaker.queue.map { it.id }.toSet()
        fun spoken(step: ClosedLoopSpeakerBranches.Step) = step.outcomes.filterIsInstance<ClosedLoopSpeakerBranches.Spoken>().map { it.id }.toSet()
        val product = baseSteps.flatMap { b -> armSteps.map { a -> b to a } }
        val agreeing = product.filter { (b, a) ->
            (spoken(b) intersect armIds).all { it in spoken(a) } && (spoken(a) intersect baseIds).all { it in spoken(b) }
        }
        return agreeing.ifEmpty { product }.map { (b, a) ->
            val off = offNominal(b, joint.base.speaker, t) + offNominal(a, joint.arm.speaker, t)
            Joint(joint.base.apply(b), joint.arm.apply(a), off.associateWith { t } + joint.deviated)
        }
    }

    /** The sentences this step has ended, or not, against what their nominal end says. */
    private fun offNominal(step: ClosedLoopSpeakerBranches.Step, speaker: ClosedLoopSpeakerBranches.Speaker, t: Long): Set<String> =
        speaker.queue.filter { item ->
            val done = step.outcomes.any { it is ClosedLoopSpeakerBranches.Spoken && it.id == item.id }
            done != (nominalEnd.getValue(item.id) <= t)
        }.mapTo(mutableSetOf()) { it.id }

    /** The step with each sentence ended or not as its nominal end says. */
    private fun nominalOf(steps: List<ClosedLoopSpeakerBranches.Step>, speaker: ClosedLoopSpeakerBranches.Speaker, t: Long): ClosedLoopSpeakerBranches.Step {
        if (steps.size == 1) return steps.single()
        return steps.firstOrNull { step ->
            speaker.queue.all { item ->
                val done = step.outcomes.any { it is ClosedLoopSpeakerBranches.Spoken && it.id == item.id }
                done == (nominalEnd.getValue(item.id) <= t)
            }
        } ?: steps.first()
    }

    /** Every result of this arm's decision, nominal first: the arm, the id it offered, the id if off its nominal result. */
    private fun decided(arm: Arm, decider: Decider, t: Long): List<Triple<Arm, String?, String?>> {
        val cooldown = arm.cooldown.copy()
        val decision = decider.decide(cooldown) ?: return listOf(Triple(Arm(arm.speaker, cooldown, arm.kept), null, null))
        val id = decision.offer.id
        creditsOf.getOrPut(id) { decision.credits }
        val steps = kernel.submit(arm.speaker, decision.offer)
        val nominal = steps.firstOrNull { it.speaker.deferred.size == arm.speaker.deferred.size } ?: steps.first()
        return (listOf(nominal) + (steps - nominal)).map { step ->
            val own = cooldown.copy()
            val accepted = step.outcomes.any { it is ClosedLoopSpeakerBranches.Accepted && it.id == id }
            if (accepted) decision.onAccepted(own) else decision.onRefused(own)
            remember(step.speaker)
            Triple(arm.apply(step, own), id, id.takeUnless { step === nominal })
        }
    }

    private fun remember(speaker: ClosedLoopSpeakerBranches.Speaker) {
        for (item in speaker.queue) nominalEnd.getOrPut(item.id) { (item.endLo + item.endHi) / 2 }
    }

    private fun resolve(t: Long) {
        if (nominalOnly) return
        val closed = mutableSetOf<StageKey>()
        for (target in pending.values) {
            val key = target.key
            if (branches.all { key in it.arm.kept }) {
                kept += key
                closed += key
            } else if (t > target.deadlineMs) {
                armLost += key
                closed += key
                val lostOnly = branches.filter { key !in it.arm.kept }
                when {
                    lostOnly.any { key in it.base.kept } -> regressions += key
                    differsNear(target.deadlineMs) -> bothLost += key
                    else -> sameInputs += key
                }
            }
        }
        if (closed.isEmpty()) return
        closed.forEach(pending::remove)
        branches = branches.map { Joint(it.base.without(closed), it.arm.without(closed), it.deviated) }.distinct()
    }

    /** The two arms' speakers and live cooldowns are equal on the nominal path, whatever each has spoken of a stage so far. */
    val nominalStatesEqual: Boolean
        get() {
            val j = branches.single()
            return j.base.speaker == j.arm.speaker &&
                liveCooldown(j.base.cooldown.canonicalState(), now) == liveCooldown(j.arm.cooldown.canonicalState(), now)
        }

    /** Whether each arm has spoken this stage in time on the nominal path. */
    fun nominalKept(key: StageKey): Pair<Boolean, Boolean> = branches.single().let { (key in it.base.kept) to (key in it.arm.kept) }

    val settledAllTargets: Boolean get() = pending.isEmpty() || capHit

    /** A run of [target] alone, starting from where this nominal run stands. */
    fun forkFor(target: BranchLoadMeasure.Target, limit: Int, inWindow: (Long) -> Boolean): PairedBranchRun {
        val run = PairedBranchRun(kernel, listOf(target), pendingQueued.toList(), inWindow, { true }, liveCooldown, limit, false, creditsOf, nominalEnd)
        val j = branches.single()
        run.now = now
        run.branches = listOf(
            run.Joint(
                run.Arm(j.base.speaker, j.base.cooldown, j.base.kept.filterKeys { it == target.key }),
                run.Arm(j.arm.speaker, j.arm.cooldown, j.arm.kept.filterKeys { it == target.key }),
                emptyMap(),
            ),
        )
        return run
    }

    fun finish(): Result {
        if (!capHit && !nominalOnly) {
            now = Long.MAX_VALUE
            branches = branches.flatMap { settle(it, Long.MAX_VALUE, window = true) }.distinct()
            resolve(Long.MAX_VALUE)
        }
        val undeterminable = all - kept - regressions - bothLost - sameInputs
        return Result(highest, capHit, framesInWindow, frames, kept, regressions, bothLost, sameInputs, armLost, undeterminable)
    }
}
