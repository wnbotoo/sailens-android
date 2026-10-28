package com.sailens.guidance.evaluation

/**
 * R10 of the threshold note: whether one sentence is spoken to the end, by a deadline, **whatever
 * end-time error up to ±ε each sentence carries on its own** -- not only when every sentence errs
 * the same way. A uniform shift misses the case review round 3 built: A, B and P a few milliseconds
 * apart, where A longer refuses B and clears the way for P, but B alone 40 ms longer blocks P.
 *
 * It runs [SpeakerModel]'s arbitration with each sentence's end as an interval instead of a point.
 * Where an arbitration step compares a time with an end the interval straddles, the run splits in
 * two -- ended by then, or still speaking -- and each half carries the narrowed interval on. The
 * result is exact for independent errors within ε: every outcome reachable that way is reached.
 * Runs whose speaker and target state are equal merge; more than [maxBranches] at once and the
 * answer is [Verdict.UNDETERMINED].
 *
 * Open loop: the offers are given. Closed-loop use (counterfactual arms, PR-1b) re-runs the decision
 * pipeline in each branch, and branches merge only when the decision state is equal too.
 */
internal class SpeakerRobustness(
    private val epsilonMs: Long = SpeakerModel.END_ERROR_BOUND_MS,
    private val startLatencyMs: Long = 0,
    private val maxBranches: Int = 64,
) {
    enum class Verdict {
        /** Spoken to the end by the deadline in every branch. */
        KEPT,

        /** Refused, cut off or late in at least one branch. */
        LOST,

        /** Too many branches to follow: neither kept nor lost can be shown. */
        UNDETERMINED,
    }

    private enum class Target { PENDING, KEPT, LOST }

    /** A sentence on the speaker; it ends somewhere in [endLo, endHi] (ms, inclusive). */
    private data class Item(val id: String, val priority: Int?, val expiresAtMs: Long?, val endLo: Long, val endHi: Long)

    private data class State(val queue: List<Item>, val ready: Boolean, val target: Target)

    fun verdict(inputs: List<SpeakerModel.Input>, targetId: String, deadlineMs: Long): Verdict {
        var states = listOf(State(emptyList(), ready = true, target = Target.PENDING))
        for (input in inputs.sortedBy { it.atMs }) {
            states = states.flatMap { advance(it, input.atMs, targetId, deadlineMs) }.flatMap { step(it, input, targetId) }.distinct()
            if (states.size > maxBranches) return Verdict.UNDETERMINED
            if (states.any { it.target == Target.LOST }) return Verdict.LOST
            if (states.all { it.target == Target.KEPT }) return Verdict.KEPT
        }
        states = states.flatMap { advance(it, Long.MAX_VALUE, targetId, deadlineMs) }
        return when {
            states.any { it.target != Target.KEPT } -> Verdict.LOST
            else -> Verdict.KEPT
        }
    }

    /** Everything that has ended by [t]; a straddled end splits the state. */
    private fun advance(state: State, t: Long, targetId: String, deadlineMs: Long): List<State> {
        val front = state.queue.firstOrNull() ?: return listOf(state)
        fun ended(item: Item): State {
            val target = if (item.id == targetId) (if (item.endHi <= deadlineMs) Target.KEPT else Target.LOST) else state.target
            return state.copy(queue = state.queue.drop(1), target = target)
        }
        return when {
            // Live iff its end is after t, as SpeakerModel.advance has it.
            front.endHi <= t -> advance(ended(front), t, targetId, deadlineMs)
            front.endLo > t -> listOf(state)
            else -> {
                val done = ended(front.copy(endHi = t))
                val live = state.copy(queue = listOf(front.copy(endLo = t + 1)) + state.queue.drop(1))
                advance(done, t, targetId, deadlineMs) + live
            }
        }
    }

    private fun step(state: State, input: SpeakerModel.Input, targetId: String): List<State> {
        val t = input.atMs
        return when (input) {
            is SpeakerModel.EngineReady -> listOf(state.copy(ready = input.ready))
            is SpeakerModel.Offer -> {
                val highest = state.queue.filter { it.priority != null && (it.expiresAtMs == null || t <= it.expiresAtMs) }.maxOfOrNull { it.priority!! }
                val refused = t > input.expiresAtMs || !state.ready || (highest != null && input.priority <= highest)
                if (refused) {
                    listOf(if (input.id == targetId) state.copy(target = Target.LOST) else state)
                } else {
                    // QUEUE_FLUSH: whatever is still on the speaker is cut off.
                    val cutTarget = state.queue.any { it.id == targetId }
                    val start = t + startLatencyMs
                    val item = Item(input.id, input.priority, input.expiresAtMs, start + input.durationMs - epsilonMs, start + input.durationMs + epsilonMs)
                    listOf(State(listOf(item), state.ready, if (cutTarget) Target.LOST else state.target))
                }
            }
            is SpeakerModel.Queued -> {
                if (!state.ready) return listOf(if (input.id == targetId) state.copy(target = Target.LOST) else state)
                val last = state.queue.lastOrNull()
                val startLo = maxOf(t + startLatencyMs, last?.endLo ?: Long.MIN_VALUE)
                val startHi = maxOf(t + startLatencyMs, last?.endHi ?: Long.MIN_VALUE)
                val expires = input.priority?.let { t + SpeakerModel.PROTECTED_NOTICE_MAX_MS }
                listOf(state.copy(queue = state.queue + Item(input.id, input.priority, expires, startLo + input.durationMs - epsilonMs, startHi + input.durationMs + epsilonMs)))
            }
        }
    }
}
