package com.sailens.guidance.evaluation

import java.util.TreeSet

/**
 * [PairedBranchRun] one stage at a time. The two arms run their nominal paths through the whole session;
 * each protected stage is judged from where those paths stand [lookBackMs] before its deadline, under the
 * per-sentence errors from there on. A stage with the same state in both arms at that point and no
 * policy difference up to its deadline needs no branches: the arms are the same in every realization.
 *
 * What this gives up: an error earlier than the look-back no longer reaches the stage. The cooldown of
 * an obstacle prompt, the longest a refusal can matter directly, is under 8 s; a chain of refusals
 * longer than that is not followed. What it gains: branches cannot pile up over a session, and a stage
 * past the limit costs that stage alone.
 */
internal class LocalPairedRun(
    kernel: ClosedLoopSpeakerBranches,
    targets: List<BranchLoadMeasure.Target>,
    queued: List<SpeakerModel.Queued>,
    private val differences: TreeSet<Long>,
    private val lookBackMs: Long,
    private val maxBranches: Int = 64,
) {
    private val nominal = PairedBranchRun(
        kernel, targets, queued,
        inWindow = { false },
        differsNear = { false },
        liveCooldown = ::liveCooldown,
        maxBranches = Int.MAX_VALUE,
        nominalOnly = true,
    )
    private val waiting = ArrayDeque(targets.sortedBy { it.deadlineMs - lookBackMs })
    private val open = mutableListOf<PairedBranchRun>()
    private val unbranched = mutableListOf<BranchLoadMeasure.Target>()
    private val done = mutableListOf<PairedBranchRun.Result>()

    private val kept = mutableSetOf<StageKey>()
    private val regressions = mutableSetOf<StageKey>()
    private val sameInputs = mutableSetOf<StageKey>()

    var forks = 0
        private set

    fun onFrame(t: Long, baseline: PairedBranchRun.Decider, arm: PairedBranchRun.Decider) {
        while (waiting.firstOrNull()?.let { it.deadlineMs - lookBackMs <= t } == true) {
            val target = waiting.removeFirst()
            val same = nominal.nominalStatesEqual && !differs(t, target.deadlineMs)
            if (same) {
                unbranched += target
            } else {
                forks++
                open += nominal.forkFor(target, maxBranches) { now -> differs(now, target.deadlineMs) }
            }
        }
        for (run in open) run.onFrame(t, baseline, arm)
        nominal.onFrame(t, baseline, arm)
        val settled = open.filter { it.settledAllTargets }
        open -= settled.toSet()
        settled.mapTo(done) { it.finish() }
        unbranched.filter { it.deadlineMs < t }.forEach(::judgeUnbranched)
    }

    private fun differs(from: Long, through: Long) = from <= through && differences.subSet(from, through + 1).isNotEmpty()

    private fun judgeUnbranched(target: BranchLoadMeasure.Target) {
        unbranched -= target
        val (base, experimental) = nominal.nominalKept(target.key)
        when {
            experimental -> kept += target.key
            base -> regressions += target.key
            else -> sameInputs += target.key
        }
    }

    fun finish(): PairedBranchRun.Result {
        open.mapTo(done) { it.finish() }
        open.clear()
        // A nominal run past its last frame has nothing more to credit; what is still waiting was never reached.
        unbranched.toList().forEach(::judgeUnbranched)
        waiting.forEach { sameInputs += it.key }
        return PairedBranchRun.Result(
            maxBranches = done.maxOfOrNull { it.maxBranches } ?: 1,
            capHit = done.any { it.capHit },
            framesInWindow = 0,
            frames = maxOf(1, nominal.finish().frames),
            kept = kept + done.flatMap { it.kept },
            regressions = regressions + done.flatMap { it.regressions },
            bothLostNearDifference = done.flatMapTo(mutableSetOf()) { it.bothLostNearDifference },
            sameInputs = sameInputs + done.flatMap { it.sameInputs },
            armLostAnyBranch = done.flatMapTo(mutableSetOf()) { it.armLostAnyBranch } + sameInputs + regressions,
            undeterminable = done.flatMapTo(mutableSetOf()) { it.undeterminable },
        )
    }
}
