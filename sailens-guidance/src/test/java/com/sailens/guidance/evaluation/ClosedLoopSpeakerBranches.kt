package com.sailens.guidance.evaluation

/**
 * The speaker of a closed-loop branch run (threshold note R10): [SpeakerModel]'s arbitration with each
 * sentence's end as an interval, as [SpeakerRobustness] has it, but one step at a time, so that a caller
 * can carry the cooldown and the delivery protocol with each branch. Used for the experimental arm only.
 *
 * With `heldAfterDone = false` and `frameSkewMs = 0` it is the frozen semantics; `SpeakerBranchEquivalenceTest`
 * checks that against the two frozen classes. The two parameters add what production can do beyond them:
 *
 * - **[frameSkewMs]**, the δ between a decision's time, where the model compares, and the frame's time,
 *   where production compares. An end in `(t, t + δ]` may or may not be over by the frame, and the same
 *   for an expiry in `[t, t + δ)`, so both are followed. The sentence's start stays at the decision's
 *   time: the ε of R10 measures exactly the error of predicting from there. The branch that stays
 *   "still speaking" remembers it was compared at `t`, because in production all calls of a frame see
 *   one state. A frame beyond δ is not covered, and the targets it can reach are undeterminable.
 * - **[heldAfterDone]**, an `onDone` that arrives while a frame runs: production settles only at the start
 *   of a frame, so such a sentence still holds the speaker until something supersedes it. If that
 *   happens, the sentence was heard all the same, reported after the frame; or `onDone` came only after
 *   the flush, a cut followed by a late "heard". The speaker is the same in all of these; the caller's
 *   state is not. A result waiting to be delivered is part of the state.
 *
 * @param epsilonMs per sentence: 150 ms, or 300 ms for a fallback wording (R10).
 */
internal class ClosedLoopSpeakerBranches(
    private val epsilonMs: (String) -> Long = { SpeakerModel.END_ERROR_BOUND_MS },
    private val heldAfterDone: Boolean = true,
    private val frameSkewMs: Long = FRAME_SKEW_MS,
    private val startLatencyMs: Long = 0,
) {
    sealed interface Outcome {
        val id: String
    }

    data class Accepted(override val id: String, val startAtMs: Long) : Outcome

    data class Refused(override val id: String, val reason: String) : Outcome

    /** Spoken to the end, by [endAtMs] at the latest. */
    data class Spoken(override val id: String, val endAtMs: Long) : Outcome

    data class Cut(override val id: String, val atMs: Long) : Outcome

    data class HeardAfterCut(override val id: String) : Outcome

    /** A sentence on the speaker, ending somewhere in `[endLo, endHi]`. */
    data class Item(
        val id: String,
        val priority: Int?,
        val expiresAtMs: Long?,
        val startAtMs: Long,
        val endLo: Long,
        val endHi: Long,
    )

    /** A result that reaches the caller only after the frame at [afterMs]. */
    data class Deferred(val outcome: Outcome, val afterMs: Long)

    /** A value, so equal branches compare equal and can merge. */
    data class Speaker(
        val queue: List<Item> = emptyList(),
        val ready: Boolean = true,
        val comparedAt: Long? = null,
        val deferred: List<Deferred> = emptyList(),
    ) {
        val idle: Boolean get() = queue.isEmpty() && deferred.isEmpty()
    }

    data class Step(val speaker: Speaker, val outcomes: List<Outcome>)

    fun start(): Speaker = Speaker()

    /** Everything that has ended by [t], and results left over from an earlier frame; a straddled end splits. */
    fun settle(speaker: Speaker, t: Long): List<Step> {
        val (due, later) = speaker.deferred.partition { it.afterMs < t }
        val current = speaker.copy(deferred = later)
        val delivered = due.map { it.outcome }
        if (frameSkewMs > 0 && current.comparedAt == t) return listOf(Step(current, delivered))
        return expand(current, t, delivered).map { step ->
            if (frameSkewMs > 0) step.copy(speaker = step.speaker.copy(comparedAt = t)) else step
        }
    }

    /**
     * The same split at a moment that is no frame, with nothing compared there and no skew: a stage's
     * deadline, so that a sentence ending around it has both outcomes. Results stay for the next frame.
     */
    fun splitAt(speaker: Speaker, t: Long): List<Step> = expand(speaker, t, emptyList(), skewMs = 0)

    private fun expand(speaker: Speaker, t: Long, outcomes: List<Outcome>, skewMs: Long = frameSkewMs): List<Step> {
        val front = speaker.queue.firstOrNull() ?: return listOf(Step(speaker, outcomes))
        val rest = speaker.copy(queue = speaker.queue.drop(1))
        return when {
            // Live iff its end is after t, as SpeakerModel.advance has it, at any frame skew.
            front.endHi <= t -> expand(rest, t, outcomes + Spoken(front.id, front.endHi), skewMs)
            front.endLo > t + skewMs -> listOf(Step(speaker, outcomes))
            else -> {
                val done = expand(rest, t, outcomes + Spoken(front.id, minOf(front.endHi, t + skewMs)), skewMs)
                val live = speaker.copy(queue = listOf(front.copy(endLo = maxOf(front.endLo, t + 1))) + speaker.queue.drop(1))
                done + Step(live, outcomes)
            }
        }
    }

    /** One input at its time. Call [settle] for that time first. */
    fun submit(speaker: Speaker, input: SpeakerModel.Input): List<Step> {
        val t = input.atMs
        return when (input) {
            is SpeakerModel.EngineReady -> listOf(Step(speaker.copy(ready = input.ready), emptyList()))
            is SpeakerModel.Queued -> listOf(queue(speaker, input))
            is SpeakerModel.Offer -> {
                val expiredChoices = when {
                    t > input.expiresAtMs -> listOf(true)
                    input.expiresAtMs < t + frameSkewMs -> listOf(false, true)
                    else -> listOf(false)
                }
                expiredChoices.flatMap { expired ->
                    highestChoices(speaker, t).flatMap { highest ->
                        val refusal = when {
                            expired -> "expired"
                            !speaker.ready -> "engine_not_ready"
                            highest != null && input.priority <= highest -> "outranked"
                            else -> null
                        }
                        if (refusal != null) listOf(Step(speaker, listOf(Refused(input.id, refusal)))) else flush(speaker, input)
                    }
                }.distinct()
            }
        }
    }

    /** The highest priority that holds the speaker, for each set of expiries a frame's skew can have passed. */
    private fun highestChoices(speaker: Speaker, t: Long): List<Int?> {
        val live = speaker.queue.filter { it.priority != null && (it.expiresAtMs == null || t <= it.expiresAtMs) }
        val uncertain = live.mapNotNull { it.expiresAtMs }.filter { it < t + frameSkewMs }.distinct().sorted()
        if (uncertain.isEmpty()) return listOf(live.maxOfOrNull { it.priority!! })
        return (listOf(null) + uncertain).map { passed ->
            live.filter { passed == null || it.expiresAtMs == null || it.expiresAtMs > passed }.maxOfOrNull { it.priority!! }
        }.distinct()
    }

    private fun flush(speaker: Speaker, offer: SpeakerModel.Offer): List<Step> {
        val t = offer.atMs
        val start = t + startLatencyMs
        val epsilon = epsilonMs(offer.id)
        val item = Item(offer.id, offer.priority, offer.expiresAtMs, start, start + offer.durationMs - epsilon, start + offer.durationMs + epsilon)
        val accepted = Accepted(offer.id, start)
        // Later results are kept: they belong to what was superseded in an earlier frame.
        val after = speaker.copy(queue = listOf(item))
        val cuts = speaker.queue.map { Cut(it.id, maxOf(t, it.startAtMs)) }
        val front = speaker.queue.firstOrNull()
        val plain = Step(after, cuts + accepted)
        // δ is 0 only for the equivalence test: the window then still has the millisecond after the compare.
        val window = t + maxOf(frameSkewMs, 1)
        if (!heldAfterDone || front == null || front.endLo > window || front.startAtMs > t) return listOf(plain)
        val heardBy = minOf(front.endHi, window)
        val heldUntilFlush = Step(
            after.copy(deferred = after.deferred + Deferred(Spoken(front.id, heardBy), t)),
            cuts.drop(1) + accepted,
        )
        val heardAfterCut = Step(after.copy(deferred = after.deferred + Deferred(HeardAfterCut(front.id), t)), cuts + accepted)
        return listOf(heldUntilFlush, plain, heardAfterCut)
    }

    private fun queue(speaker: Speaker, input: SpeakerModel.Queued): Step {
        if (!speaker.ready) return Step(speaker, listOf(Refused(input.id, "engine_not_ready")))
        val t = input.atMs
        val last = speaker.queue.lastOrNull()
        val startLo = maxOf(t + startLatencyMs, last?.endLo ?: Long.MIN_VALUE)
        val startHi = maxOf(t + startLatencyMs, last?.endHi ?: Long.MIN_VALUE)
        val expires = input.priority?.let { t + SpeakerModel.PROTECTED_NOTICE_MAX_MS }
        val epsilon = epsilonMs(input.id)
        val item = Item(input.id, input.priority, expires, startLo, startLo + input.durationMs - epsilon, startHi + input.durationMs + epsilon)
        return Step(speaker.copy(queue = speaker.queue + item), listOf(Accepted(input.id, startLo)))
    }

    /**
     * A fixed input stream and one target, as [SpeakerRobustness.check] has it, for checking this kernel
     * against it; the same verdict rules and the same branch limit.
     */
    fun check(
        inputs: List<SpeakerModel.Input>,
        targetId: String,
        deadlineMs: Long = Long.MAX_VALUE,
        maxBranches: Int = 64,
    ): SpeakerRobustness.Outcome {
        var branches = listOf(TargetBranch(start()))
        fun apply(branch: TargetBranch, step: Step) = branch.after(step, targetId, deadlineMs)
        for (input in inputs.sortedBy { it.atMs }) {
            branches = branches.flatMap { b -> settle(b.speaker, input.atMs).map { apply(b, it) } }
                .flatMap { b -> submit(b.speaker, input).map { apply(b, it) } }
                .distinct()
            if (branches.size > maxBranches) return SpeakerRobustness.Outcome(SpeakerRobustness.Verdict.UNDETERMINED)
            if (branches.any { it.target == TargetMark.LOST }) return SpeakerRobustness.Outcome(SpeakerRobustness.Verdict.LOST)
            if (branches.all { it.target == TargetMark.KEPT }) {
                return SpeakerRobustness.Outcome(SpeakerRobustness.Verdict.KEPT, branches.maxOf { it.targetEndMs!! })
            }
        }
        branches = branches.flatMap { b -> settle(b.speaker, Long.MAX_VALUE).map { apply(b, it) } }
        return when {
            branches.any { it.target != TargetMark.KEPT } -> SpeakerRobustness.Outcome(SpeakerRobustness.Verdict.LOST)
            else -> SpeakerRobustness.Outcome(SpeakerRobustness.Verdict.KEPT, branches.maxOf { it.targetEndMs!! })
        }
    }

    private enum class TargetMark { PENDING, KEPT, LOST }

    private data class TargetBranch(val speaker: Speaker, val target: TargetMark = TargetMark.PENDING, val targetEndMs: Long? = null) {
        fun after(step: Step, targetId: String, deadlineMs: Long): TargetBranch {
            var mark = target
            var end = targetEndMs
            for (outcome in step.outcomes) {
                if (outcome.id != targetId) continue
                when (outcome) {
                    is Refused, is Cut -> mark = TargetMark.LOST
                    is Spoken -> if (outcome.endAtMs <= deadlineMs) {
                        mark = TargetMark.KEPT
                        end = outcome.endAtMs
                    } else {
                        mark = TargetMark.LOST
                    }
                    is Accepted, is HeardAfterCut -> Unit
                }
            }
            return TargetBranch(step.speaker, mark, end)
        }
    }

    companion object {
        /**
         * δ. It covers what was measured on the calibration set from a decision to its first speech
         * record (at most 11.3 ms over 2 372 records); that does not bound the time to a flush, and a
         * frame with no prompt has no record at all, so the run reports both.
         */
        const val FRAME_SKEW_MS: Long = 50
    }
}
