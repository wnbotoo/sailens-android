package com.sailens.guidance.evaluation

import com.sailens.guidance.processor.decision.CooldownManager
import java.util.IdentityHashMap

/**
 * The paired reading of M5: both arms, each deciding for itself on every frame, under every realization of
 * the per-sentence errors within ε. A stage is a regression if some realization has the baseline speaking it
 * by its deadline and the arm not. An arm that says exactly what the baseline says cannot score one, and that
 * is observed, not assumed: nothing here is told whether the arms differ.
 *
 * Each joint branch holds both arms. While the two are in equal state they split together, as one sentence in
 * one realization ends at one moment. Once apart, each arm splits for itself, except that a sentence both
 * have, started at the same known moment, has one outcome. A stage's deadline and a queued input's time are
 * moments to split at like any frame, so a sentence ending around them has both outcomes. Two branches merge
 * only when all their state is equal, whatever brought them there.
 *
 * This over-approximates and never under-approximates. A queued sentence's interval does not narrow once what
 * is ahead of it has ended, and two arms apart share no frame lateness and no sentence with an unknown start,
 * so a branch may stand for no real realization: that can only add to what an arm is charged with.
 *
 * It grows without bound. Past [maxBranches] live branches, or [maxBranchFrames] branches summed over the
 * frames, the run stops: what it settled before that stands, and the rest is [Result.undeterminable]. Both are
 * counts, so a run has one answer on any machine.
 *
 * An arm with more state than a speaker and a cooldown (a delivery protocol) has to add it to [Arm], or two
 * branches that differ there merge.
 *
 * @param watches stages asked of each arm on its own, whatever the other does (M1, M2, B5, C3).
 */
internal class PairedBranchRun(
    private val kernel: ClosedLoopSpeakerBranches,
    targets: List<Target>,
    queued: List<SpeakerModel.Queued>,
    watches: List<Watch> = emptyList(),
    private val liveCooldown: (CooldownManager.Canonical, Long) -> CooldownManager.Canonical = ::liveCooldown,
    private val maxBranches: Int = 4_096,
    private val maxBranchFrames: Long = Long.MAX_VALUE,
) {
    /** @param appearedAtMs when the stage first appeared on the device, before anything could be offered for it. */
    class Target(val key: StageKey, val deadlineMs: Long, val appearedAtMs: Long)

    /** @param closesAtMs by when no sentence still to come can speak for the stage. A sentence not over by the first frame after it does not count. */
    class Watch(val key: StageKey, val closesAtMs: Long)

    class Decision(val offer: SpeakerModel.Offer, val credits: List<StageKey>, val onAccepted: (CooldownManager) -> Unit, val onRefused: (CooldownManager) -> Unit)

    /** One arm's answer for this frame from this cooldown; it may write to the cooldown it is given. */
    fun interface Decider {
        fun decide(cooldown: CooldownManager): Decision?
    }

    /** One arm's answers to the [Watch]es. */
    class Completions(
        /** Spoken to the end in every realization. */
        val everywhere: Set<StageKey>,
        /** The run stopped before it could say. */
        val unresolved: Set<StageKey>,
        /** The latest first completion over the realizations that spoke it; for a stage in [everywhere], over all of them. */
        val latestEndMs: Map<StageKey, Long>,
    ) {
        /** What a metric may take as the stage's completion: the latest, and only if every realization has one. */
        fun completionMs(key: StageKey): Long? = latestEndMs[key]?.takeIf { key in everywhere }
    }

    class Result(
        val maxBranches: Int,
        val capHit: Boolean,
        val branchFrames: Long,
        /** Spoken in time in every realization. */
        val kept: Set<StageKey>,
        /** Some realization has the baseline keeping the stage and the arm not. */
        val regressions: Set<StageKey>,
        /** Never the baseline keeping it where the arm did not; in some losing realization the arms had differed since before the stage appeared. */
        val bothLostApart: Set<StageKey>,
        /** Lost only where the two arms had run the same, state and inputs, since before the stage appeared. */
        val sameInputs: Set<StageKey>,
        /** Not kept in some realization, whatever the baseline did: what an every-branch rule looks at. */
        val armLostSomewhere: Set<StageKey>,
        val undeterminable: Set<StageKey>,
        /** For each regression, the choices of one realization that shows it. */
        val witnesses: Map<StageKey, List<String>>,
        val baseline: Completions,
        val arm: Completions,
    )

    private class Arm(
        val speaker: ClosedLoopSpeakerBranches.Speaker,
        val cooldown: CooldownManager,
        /** What of the cooldown can still matter: state, as the cooldown itself is not comparable. */
        val live: CooldownManager.Canonical,
        val kept: Set<StageKey>,
        val heard: Set<StageKey>,
    ) {
        private val hash = 31 * (31 * (31 * speaker.hashCode() + live.hashCode()) + kept.hashCode()) + heard.hashCode()

        override fun hashCode() = hash

        override fun equals(other: Any?) = this === other ||
            (other is Arm && hash == other.hash && speaker == other.speaker && live == other.live && kept == other.kept && heard == other.heard)
    }

    /** One realization's choices so far, shared with the branches it split into. */
    private class Choice(val note: String, val before: Choice?) {
        fun toList(): List<String> = generateSequence(this) { it.before }.map { it.note }.toList().asReversed()
    }

    private class Joint(val base: Arm, val arm: Arm, val choices: Choice?, sameSince: Long?) {
        val coupled: Boolean get() = base == arm

        /** The two arms have had equal state and equal inputs since this time; null while they differ. */
        var sameSince: Long? = sameSince.takeIf { coupled }
            private set

        /** On equal state the futures are equal, so only the worse past needs keeping: a stage is exempt only if every way here is. */
        fun absorb(other: Joint) {
            val mine = sameSince
            val theirs = other.sameSince
            sameSince = if (mine == null || theirs == null) null else maxOf(mine, theirs)
        }

        override fun equals(other: Any?) = other is Joint && base == other.base && arm == other.arm
        override fun hashCode() = 31 * base.hashCode() + arm.hashCode()
    }

    /** One way an arm can go on from here. */
    private class Move(val arm: Arm, val spoken: Set<String>, val note: String?, val offer: SpeakerModel.Offer? = null)

    /** A frame's decision from one cooldown, and what comes of it. Cooldowns are not written after this, so branches share them. */
    private class Decided(val cooldown: CooldownManager, val decision: Decision?) {
        val accepted by lazy { cooldown.copy().also { decision!!.onAccepted(it) } }
        val refused by lazy { cooldown.copy().also { decision!!.onRefused(it) } }
    }

    private var now = 0L
    private val credits = listOf(HashMap<String, List<StageKey>>(), HashMap<String, List<StageKey>>())
    private val pending = targets.associateBy { it.key }.toMutableMap()
    private val all = pending.keys.toSet()
    private val pendingQueued = ArrayDeque(queued.sortedBy { it.atMs })
    private val open = watches.associateBy { it.key }.toMutableMap()
    private val allWatched = open.keys.toSet()

    private var capHit = false
    private var highest = 1
    private var branchFrames = 0L
    private val kept = mutableSetOf<StageKey>()
    private val regressions = mutableSetOf<StageKey>()
    private val bothLostApart = mutableSetOf<StageKey>()
    private val sameInputs = mutableSetOf<StageKey>()
    private val armLost = mutableSetOf<StageKey>()
    private val witnesses = HashMap<StageKey, List<String>>()
    private val everywhere = listOf(mutableSetOf<StageKey>(), mutableSetOf<StageKey>())
    private val missed = listOf(mutableSetOf<StageKey>(), mutableSetOf<StageKey>())
    private val latestEndMs = listOf(HashMap<StageKey, Long>(), HashMap<StageKey, Long>())

    /** Most of what a frame does is the same for many branches: they differ in the other arm, or only in the speaker. */
    private val liveOf = IdentityHashMap<CooldownManager, CooldownManager.Canonical>()
    private val interned = HashMap<Arm, Arm>()
    private val moves = listOf(HashMap<Arm, List<Move>>(), HashMap<Arm, List<Move>>())
    private val decisions = listOf(HashMap<CooldownManager.Canonical, Decided>(), HashMap<CooldownManager.Canonical, Decided>())
    private val offered = listOf(HashMap<Arm, List<Move>>(), HashMap<Arm, List<Move>>())

    private fun enter(t: Long) {
        now = t
        liveOf.clear()
        interned.clear()
        moves.forEach { it.clear() }
    }

    private fun arm(speaker: ClosedLoopSpeakerBranches.Speaker, cooldown: CooldownManager, kept: Set<StageKey>, heard: Set<StageKey>): Arm {
        val live = liveOf.getOrPut(cooldown) { liveCooldown(cooldown.canonicalState(), now) }
        val made = Arm(speaker, cooldown, live, kept, heard)
        return interned.getOrPut(made) { made }
    }

    private fun after(from: Arm, step: ClosedLoopSpeakerBranches.Step, side: Int, cooldown: CooldownManager = from.cooldown): Arm {
        var marks = from.kept
        var heard = from.heard
        for (outcome in step.outcomes) {
            if (outcome !is ClosedLoopSpeakerBranches.Spoken) continue
            for (key in credits[side][outcome.id].orEmpty()) {
                val target = pending[key]
                if (target != null && outcome.endAtMs <= target.deadlineMs) marks = marks + key
                if (key in open && key !in heard) {
                    heard = heard + key
                    latestEndMs[side].merge(key, outcome.endAtMs, ::maxOf)
                }
            }
        }
        // A result still to come that can mark nothing is no state: without this, a flush's three variants stay apart.
        val speaker = step.speaker.copy(
            deferred = step.speaker.deferred.filter { d ->
                val spoken = d.outcome as? ClosedLoopSpeakerBranches.Spoken ?: return@filter false
                credits[side][spoken.id].orEmpty().any { key ->
                    val target = pending[key]
                    (target != null && key !in marks && spoken.endAtMs <= target.deadlineMs) || (key in open && key !in heard)
                }
            },
        )
        return arm(speaker, cooldown, marks, heard)
    }

    private fun movesOf(from: Arm, side: Int, by: (ClosedLoopSpeakerBranches.Speaker) -> List<ClosedLoopSpeakerBranches.Step>): List<Move> =
        moves[side].getOrPut(from) {
            val steps = by(from.speaker)
            steps.map { step ->
                val spoken = step.outcomes.filterIsInstance<ClosedLoopSpeakerBranches.Spoken>().map { it.id }.toSet()
                val note = if (steps.size == 1) null else "${spoken.ifEmpty { setOf("nothing") }} over by $now"
                Move(after(from, step, side), spoken, note)
            }
        }

    /** Sentences that have one outcome in both arms: the same one, the same start, known, with all that is ahead of it the same too. */
    private fun shared(joint: Joint): Set<String> {
        val a = joint.base.speaker.queue
        val b = joint.arm.speaker.queue
        if (a.isEmpty() || b.isEmpty() || !a[0].startKnown || !b[0].startKnown) return emptySet()
        val ids = mutableSetOf<String>()
        for (i in 0 until minOf(a.size, b.size)) {
            if (a[i].id != b[i].id || a[i].startAtMs != b[i].startAtMs || a[i].endHi != b[i].endHi) break
            ids += a[i].id
        }
        return ids
    }

    private fun split(joint: Joint, by: (ClosedLoopSpeakerBranches.Speaker) -> List<ClosedLoopSpeakerBranches.Step>): List<Joint> {
        val bases = movesOf(joint.base, 0, by)
        val arms = movesOf(joint.arm, 1, by)
        if (joint.coupled) {
            return bases.indices.map { i -> Joint(bases[i].arm, arms[i].arm, noted(joint.choices, bases[i].note), joint.sameSince) }
        }
        val together = shared(joint)
        val agreeing = ArrayList<Joint>(bases.size * arms.size)
        for (b in bases) {
            for (a in arms) {
                if (together.isNotEmpty() && together.any { (it in b.spoken) != (it in a.spoken) }) continue
                agreeing += Joint(b.arm, a.arm, noted(noted(joint.choices, b.note), a.note.takeIf { it != b.note }), null)
            }
        }
        if (agreeing.isNotEmpty()) return agreeing
        return bases.flatMap { b -> arms.map { a -> Joint(b.arm, a.arm, noted(noted(joint.choices, b.note), a.note.takeIf { it != b.note }), null) } }
    }

    private fun noted(before: Choice?, note: String?) = if (note == null) before else Choice(note, before)

    private fun merged(joints: List<Joint>): List<Joint> {
        val seen = HashMap<Joint, Joint>(joints.size * 2)
        for (j in joints) seen.putIfAbsent(j, j)?.absorb(j)
        return seen.keys.toList()
    }

    /** Every deadline and queued input up to [t], the deadline of [t] itself among them: a frame compares with its lateness, a deadline does not. */
    private fun advanceTo(t: Long): Boolean {
        val cuts = (pending.values.map { it.deadlineMs }.filter { it in now + 1..t } + pendingQueued.map { it.atMs }.filter { it <= t }).distinct().sorted()
        for (cut in cuts) {
            enter(cut)
            branches = merged(branches.flatMap { joint -> split(joint) { kernel.splitAt(it, cut) } })
            if (overLimit()) return false
            while (pendingQueued.firstOrNull()?.let { it.atMs == cut } == true) {
                val q = pendingQueued.removeFirst()
                enter(cut)
                branches = merged(branches.flatMap { joint -> split(joint) { kernel.submit(it, q) } })
            }
        }
        return true
    }

    private var branches = listOf(
        run {
            val start = arm(kernel.start(), CooldownManager(), emptySet(), emptySet())
            Joint(start, start, null, Long.MIN_VALUE)
        },
    )

    fun onFrame(t: Long, baseline: Decider, experimental: Decider) {
        if (capHit || !advanceTo(t)) return
        enter(t)
        branches = merged(branches.flatMap { joint -> split(joint) { kernel.settle(it, t) } })
        if (overLimit()) return
        resolve(t)
        decisions.forEach { it.clear() }
        offered.forEach { it.clear() }
        val next = ArrayList<Joint>(branches.size)
        for (joint in branches) {
            val bases = decided(joint.base, 0, baseline, t)
            val arms = decided(joint.arm, 1, experimental, t)
            val inStep = joint.coupled && bases.first().offer == arms.first().offer
            if (inStep) {
                for (i in bases.indices) next += framed(joint, bases[i], arms[i], t)
            } else {
                for (b in bases) for (a in arms) next += framed(joint, b, a, t)
            }
        }
        branches = merged(next)
        if (overLimit()) return
        branchFrames += branches.size
        if (branchFrames > maxBranchFrames) capHit = true
    }

    private fun framed(joint: Joint, b: Move, a: Move, t: Long): Joint {
        val same = b.offer == a.offer && b.arm == a.arm
        return Joint(b.arm, a.arm, noted(noted(joint.choices, b.note), a.note.takeIf { it != b.note }), if (same) joint.sameSince ?: t else null)
    }

    private fun overLimit(): Boolean {
        highest = maxOf(highest, branches.size)
        if (branches.size <= maxBranches) return false
        capHit = true
        return true
    }

    /** Every result of this arm's decision on this frame. */
    private fun decided(from: Arm, side: Int, decider: Decider, t: Long): List<Move> = offered[side].getOrPut(from) {
        val memo = decisions[side].getOrPut(from.live) {
            val fresh = from.cooldown.copy()
            Decided(fresh, decider.decide(fresh))
        }
        val decision = memo.decision ?: return@getOrPut listOf(Move(arm(from.speaker, memo.cooldown, from.kept, from.heard), emptySet(), null))
        val id = decision.offer.id
        val known = credits[side].getOrPut(id) { decision.credits }
        check(known == decision.credits) { "$id speaks for different stages in one arm" }
        val steps = kernel.submit(from.speaker, decision.offer)
        steps.mapIndexed { i, step ->
            val accepted = step.outcomes.any { it is ClosedLoopSpeakerBranches.Accepted && it.id == id }
            Move(after(from, step, side, if (accepted) memo.accepted else memo.refused), emptySet(), "$id outcome $i of ${steps.size} at $t".takeIf { steps.size > 1 }, decision.offer)
        }
    }

    private fun resolve(t: Long) {
        val closedTargets = mutableSetOf<StageKey>()
        for (target in pending.values) {
            val key = target.key
            if (branches.all { key in it.arm.kept }) {
                kept += key
                closedTargets += key
            } else if (t > target.deadlineMs) {
                armLost += key
                closedTargets += key
                val losing = branches.filter { key !in it.arm.kept }
                val witness = losing.firstOrNull { key in it.base.kept }
                when {
                    witness != null -> {
                        regressions += key
                        witnesses[key] = witness.choices?.toList().orEmpty()
                    }
                    losing.all { j -> j.sameSince?.let { it <= target.appearedAtMs } == true } -> sameInputs += key
                    else -> bothLostApart += key
                }
            }
        }
        val closedWatches = mutableSetOf<StageKey>()
        for (watch in open.values) {
            val key = watch.key
            if (key !in everywhere[0] && branches.all { key in it.base.heard }) everywhere[0] += key
            if (key !in everywhere[1] && branches.all { key in it.arm.heard }) everywhere[1] += key
            // Marks stay until both sides are done with them, so that one side's closing cannot part two arms in equal state.
            if (t > watch.closesAtMs || t == Long.MAX_VALUE || (key in everywhere[0] && key in everywhere[1])) closedWatches += key
        }
        for (key in closedWatches) {
            open.remove(key)
            for (side in 0..1) if (key !in everywhere[side]) missed[side] += key
        }
        closedTargets.forEach(pending::remove)
        if (closedTargets.isEmpty() && closedWatches.isEmpty()) return
        val stripped = IdentityHashMap<Arm, Arm>()
        fun strip(a: Arm) = stripped.getOrPut(a) { arm(a.speaker, a.cooldown, a.kept - closedTargets, a.heard - closedWatches) }
        branches = merged(branches.map { j -> Joint(strip(j.base), strip(j.arm), j.choices, j.sameSince) })
    }

    fun finish(): Result {
        if (!capHit && advanceTo(Long.MAX_VALUE)) {
            enter(Long.MAX_VALUE)
            branches = merged(branches.flatMap { joint -> split(joint) { kernel.settle(it, Long.MAX_VALUE) } })
            if (!overLimit()) resolve(Long.MAX_VALUE)
        }
        val undeterminable = all - kept - regressions - bothLostApart - sameInputs
        fun completions(side: Int) = Completions(everywhere[side].toSet(), allWatched - everywhere[side] - missed[side], latestEndMs[side].toMap())
        return Result(highest, capHit, branchFrames, kept, regressions, bothLostApart, sameInputs, armLost, undeterminable, witnesses, completions(0), completions(1))
    }
}
