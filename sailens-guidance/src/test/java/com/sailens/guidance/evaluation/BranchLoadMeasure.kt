package com.sailens.guidance.evaluation

/**
 * The branch kernel run over a fixed stream of offers with P's stages as targets: how many branches it
 * takes, whether it hits the limit, and what that leaves undeterminable. A lower bound for the closed
 * loop, which adds the cooldown and the protocol to what must be equal before two branches merge.
 */
internal class BranchLoadMeasure(private val kernel: ClosedLoopSpeakerBranches, private val maxBranches: Int = 64) {

    class Target(val key: StageKey, val creditedBy: Set<String>, val deadlineMs: Long)

    class Result(val maxBranches: Int, val capHit: Boolean, val kept: Int, val lost: Int, val undeterminable: Int, val lostKeys: Set<StageKey> = emptySet()) {
        operator fun plus(other: Result) = Result(
            maxOf(maxBranches, other.maxBranches),
            capHit || other.capHit,
            kept + other.kept,
            lost + other.lost,
            undeterminable + other.undeterminable,
            lostKeys + other.lostKeys,
        )
    }

    private data class Branch(val speaker: ClosedLoopSpeakerBranches.Speaker, val kept: Map<StageKey, Long> = emptyMap())

    fun run(inputs: List<SpeakerModel.Input>, targets: List<Target>): Result {
        val pending = targets.associateBy { it.key }.toMutableMap()
        val creditsOf = HashMap<String, MutableList<Target>>()
        for (t in targets) for (id in t.creditedBy) creditsOf.getOrPut(id) { mutableListOf() } += t
        var kept = 0
        val lost = mutableSetOf<StageKey>()
        var highest = 1

        fun apply(branch: Branch, step: ClosedLoopSpeakerBranches.Step): Branch {
            var marks = branch.kept
            for (outcome in step.outcomes) {
                if (outcome !is ClosedLoopSpeakerBranches.Spoken) continue
                for (t in creditsOf[outcome.id].orEmpty()) {
                    if (t.key in pending && t.key !in marks && outcome.endAtMs <= t.deadlineMs) marks = marks + (t.key to outcome.endAtMs)
                }
            }
            return Branch(step.speaker, marks)
        }

        var branches = listOf(Branch(kernel.start()))

        fun resolve(t: Long) {
            val closed = mutableSetOf<StageKey>()
            for (target in pending.values) {
                if (branches.all { target.key in it.kept }) {
                    kept++
                    closed += target.key
                } else if (t > target.deadlineMs) {
                    lost += target.key
                    closed += target.key
                }
            }
            if (closed.isEmpty()) return
            closed.forEach(pending::remove)
            branches = branches.map { b -> b.copy(kept = b.kept - closed) }.distinct()
        }

        for (input in inputs.sortedBy { it.atMs }) {
            branches = branches.flatMap { b -> kernel.settle(b.speaker, input.atMs).map { apply(b, it) } }
                .flatMap { b -> kernel.submit(b.speaker, input).map { apply(b, it) } }
                .distinct()
            highest = maxOf(highest, branches.size)
            if (branches.size > maxBranches) return Result(highest, true, kept, lost.size, pending.size, lost)
            resolve(input.atMs)
        }
        branches = branches.flatMap { b -> kernel.settle(b.speaker, Long.MAX_VALUE).map { apply(b, it) } }.distinct()
        resolve(Long.MAX_VALUE)
        return Result(highest, false, kept, lost.size, pending.size, lost)
    }
}
