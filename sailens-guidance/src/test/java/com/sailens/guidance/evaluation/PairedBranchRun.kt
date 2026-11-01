package com.sailens.guidance.evaluation

import com.sailens.guidance.processor.decision.CooldownManager

/**
 * The exact reference for [PairedEvaluation]: both arms under every realization of the per-sentence errors,
 * nothing pruned. Each joint branch holds both arms; a straddled end splits both together, and a stage's
 * deadline is a moment to split at like any frame, so a sentence ending around it has both outcomes. Two
 * branches merge only when all their state is equal. It grows without bound, so it hits its limit on long
 * runs; a capture it settles is an exact answer, and one it does not is no answer at all.
 */
internal class PairedBranchRun(
    private val kernel: ClosedLoopSpeakerBranches,
    targets: List<PairedEvaluation.Target>,
    queued: List<SpeakerModel.Queued>,
    private val liveCooldown: (CooldownManager.Canonical, Long) -> CooldownManager.Canonical = ::liveCooldown,
    private val maxBranches: Int = 4_096,
) {
    class Decision(val offer: SpeakerModel.Offer, val credits: List<StageKey>, val onAccepted: (CooldownManager) -> Unit, val onRefused: (CooldownManager) -> Unit)

    /** One arm's answer for this frame from this cooldown; it may write to the cooldown it is given. */
    fun interface Decider {
        fun decide(cooldown: CooldownManager): Decision?
    }

    class Result(
        val maxBranches: Int,
        val capHit: Boolean,
        /** Spoken in time in every realization looked at. */
        val kept: Set<StageKey>,
        /** Some realization has the baseline keeping the stage and the arm not. */
        val regressions: Set<StageKey>,
        /** Not kept by either arm in the same realization, the arms having differed since the stage appeared. */
        val bothLostApart: Set<StageKey>,
        /** Not kept by either arm, the two the same in state and inputs since before the stage appeared. */
        val sameInputs: Set<StageKey>,
        /** Not kept in some realization, whatever the baseline did: what an every-branch rule looks at. */
        val armLostSomewhere: Set<StageKey>,
        val undeterminable: Set<StageKey>,
        /** For each regression, the choices of one realization that shows it. */
        val witnesses: Map<StageKey, List<String>> = emptyMap(),
    )

    private inner class Arm(val speaker: ClosedLoopSpeakerBranches.Speaker, val cooldown: CooldownManager, val kept: Set<StageKey>) {
        private val identity = Triple(speaker, liveCooldown(cooldown.canonicalState(), now), kept)
        override fun equals(other: Any?) = other is Arm && identity == other.identity
        override fun hashCode() = identity.hashCode()

        fun apply(step: ClosedLoopSpeakerBranches.Step, side: Int, cooldown: CooldownManager = this.cooldown): Arm {
            var marks = kept
            for (outcome in step.outcomes) {
                if (outcome !is ClosedLoopSpeakerBranches.Spoken) continue
                for (key in credits[side][outcome.id].orEmpty()) {
                    val target = pending[key] ?: continue
                    if (outcome.endAtMs <= target.deadlineMs) marks = marks + key
                }
            }
            return Arm(step.speaker, cooldown, marks)
        }
    }

    /** [choices] is no part of the state: it records one way here. */
    private inner class Joint(val base: Arm, val arm: Arm, val choices: List<String>) {
        val coupled: Boolean get() = base == arm
        override fun equals(other: Any?) = other is Joint && base == other.base && arm == other.arm
        override fun hashCode() = 31 * base.hashCode() + arm.hashCode()
    }

    private var now = 0L
    private val credits = listOf(HashMap<String, List<StageKey>>(), HashMap<String, List<StageKey>>())
    private val pending = targets.associateBy { it.key }.toMutableMap()
    private val all = pending.keys.toSet()
    private val pendingQueued = ArrayDeque(queued.sortedBy { it.atMs })
    private var branches = listOf(Joint(Arm(kernel.start(), CooldownManager(), emptySet()), Arm(kernel.start(), CooldownManager(), emptySet()), emptyList()))

    private var capHit = false
    private var highest = 1
    private val kept = mutableSetOf<StageKey>()
    private val regressions = mutableSetOf<StageKey>()
    private val bothLost = mutableSetOf<StageKey>()
    private val armLost = mutableSetOf<StageKey>()
    private val witnesses = HashMap<StageKey, List<String>>()

    fun onFrame(t: Long, baseline: Decider, arm: Decider) {
        if (capHit) return
        val cuts = pending.values.map { it.deadlineMs }.filter { it in now until t }.distinct().sorted()
        for (cut in cuts) {
            now = cut
            branches = merged(branches.flatMap { joint -> split(joint, cut, kernel::splitAt) })
            if (overLimit()) return
        }
        now = t

        var settled = branches.flatMap { split(it, t, kernel::settle) }
        while (pendingQueued.firstOrNull()?.let { it.atMs <= t } == true) {
            val q = pendingQueued.removeFirst()
            settled = settled.map { j ->
                Joint(j.base.apply(kernel.submit(j.base.speaker, q).single(), 0), j.arm.apply(kernel.submit(j.arm.speaker, q).single(), 1), j.choices)
            }
        }
        val next = ArrayList<Joint>()
        for (joint in settled) {
            val bases = decided(joint.base, 0, baseline, t)
            val arms = decided(joint.arm, 1, arm, t)
            val pairs = if (joint.coupled && bases.size == arms.size && bases.first().second == arms.first().second) {
                bases.zip(arms)
            } else {
                bases.flatMap { b -> arms.map { a -> b to a } }
            }
            pairs.mapTo(next) { (b, a) -> Joint(b.first, a.first, joint.choices + listOfNotNull(b.third, a.third.takeIf { a.third != b.third })) }
        }
        branches = merged(next)
        if (overLimit()) return
        resolve(t)
    }

    private fun overLimit(): Boolean {
        highest = maxOf(highest, branches.size)
        if (branches.size <= maxBranches) return false
        capHit = true
        return true
    }

    private fun merged(joints: List<Joint>): List<Joint> {
        val seen = LinkedHashMap<Joint, Joint>()
        for (j in joints) seen.putIfAbsent(j, j)
        return seen.keys.toList()
    }

    /** A straddled end splits both arms together where they share the sentence, and the one that has it where not. */
    private fun split(joint: Joint, t: Long, by: (ClosedLoopSpeakerBranches.Speaker, Long) -> List<ClosedLoopSpeakerBranches.Step>): List<Joint> {
        val baseSteps = by(joint.base.speaker, t)
        fun spoken(step: ClosedLoopSpeakerBranches.Step) = step.outcomes.filterIsInstance<ClosedLoopSpeakerBranches.Spoken>().map { it.id }.toSet()
        fun note(steps: List<ClosedLoopSpeakerBranches.Step>, step: ClosedLoopSpeakerBranches.Step) =
            if (steps.size == 1) emptyList() else spoken(step).map { "$it ended by $t" }.ifEmpty { listOf("still speaking at $t") }
        if (joint.coupled) return baseSteps.map { s -> Joint(joint.base.apply(s, 0), joint.arm.apply(s, 1), joint.choices + note(baseSteps, s)) }
        val armSteps = by(joint.arm.speaker, t)
        val baseIds = joint.base.speaker.queue.map { it.id }.toSet()
        val armIds = joint.arm.speaker.queue.map { it.id }.toSet()
        val product = baseSteps.flatMap { b -> armSteps.map { a -> b to a } }
        val agreeing = product.filter { (b, a) ->
            (spoken(b) intersect armIds).all { it in spoken(a) } && (spoken(a) intersect baseIds).all { it in spoken(b) }
        }
        return agreeing.ifEmpty { product }.map { (b, a) ->
            Joint(joint.base.apply(b, 0), joint.arm.apply(a, 1), joint.choices + (note(baseSteps, b) + note(armSteps, a)).distinct())
        }
    }

    /** Every result of this arm's decision: the arm, the id it offered, a note if the offer had other outcomes. */
    private fun decided(arm: Arm, side: Int, decider: Decider, t: Long): List<Triple<Arm, String?, String?>> {
        val cooldown = arm.cooldown.copy()
        val decision = decider.decide(cooldown) ?: return listOf(Triple(Arm(arm.speaker, cooldown, arm.kept), null, null))
        val id = decision.offer.id
        credits[side].getOrPut(id) { decision.credits }
        val steps = kernel.submit(arm.speaker, decision.offer)
        return steps.mapIndexed { i, step ->
            val own = cooldown.copy()
            val accepted = step.outcomes.any { it is ClosedLoopSpeakerBranches.Accepted && it.id == id }
            if (accepted) decision.onAccepted(own) else decision.onRefused(own)
            Triple(arm.apply(step, side, own), id, "$id outcome $i of ${steps.size}".takeIf { steps.size > 1 })
        }
    }

    private fun resolve(t: Long) {
        val closed = mutableSetOf<StageKey>()
        for (target in pending.values) {
            val key = target.key
            if (branches.all { key in it.arm.kept }) {
                kept += key
                closed += key
            } else if (t > target.deadlineMs) {
                armLost += key
                closed += key
                val witness = branches.firstOrNull { key !in it.arm.kept && key in it.base.kept }
                if (witness != null) {
                    regressions += key
                    witnesses[key] = witness.choices
                } else {
                    bothLost += key
                }
            }
        }
        if (closed.isEmpty()) return
        closed.forEach(pending::remove)
        branches = merged(branches.map { j -> Joint(Arm(j.base.speaker, j.base.cooldown, j.base.kept - closed), Arm(j.arm.speaker, j.arm.cooldown, j.arm.kept - closed), j.choices) })
    }

    fun finish(): Result {
        if (!capHit) {
            now = Long.MAX_VALUE
            branches = merged(branches.flatMap { split(it, Long.MAX_VALUE, kernel::settle) })
            resolve(Long.MAX_VALUE)
        }
        val undeterminable = all - kept - regressions - bothLost
        return Result(highest, capHit, kept, regressions, bothLost, emptySet(), armLost, undeterminable, witnesses)
    }
}
