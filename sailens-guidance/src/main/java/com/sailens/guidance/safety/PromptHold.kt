package com.sailens.guidance.safety

/**
 * After Guidance had to stop, when ordinary prompts may be given again: not until enough fresh, usable results show
 * it can judge the way again.
 *
 * Its owner steps it once per input, with the stop cause that input leaves (null when none) and the usable result it
 * brought, if any. A cause that appears, or changes, begins a stop: the hold starts, from that moment ([Held.sinceMs]).
 * While the same cause holds, the stop goes on. Once no cause holds, the hold recovers: a result whose frame was
 * received after the stop began is fresh and counts; one received at or before it is stale, a picture taken before
 * Guidance knew it could not judge, and neither counts nor resets the count. The hold is released by the result that
 * makes [minFreshResults] fresh results whose receipts span at least [minFreshSpanMs]: the span is picture time, not
 * time the user waits. A cause appearing again begins a new stop.
 *
 * A result whose receipt the source did not record counts at the time it is stepped: it cannot prove itself fresh,
 * and [Held.receiptFallbacks] says how many did. Counting none of them would leave a hold that never ends, with no
 * alert repeating while it recovers.
 *
 * Pure, and not thread-safe: the owner calls it from one thread.
 *
 * @param C the stop's cause, only told apart: a different cause begins a new stop.
 */
class PromptHold<C : Any>(
    val minFreshResults: Int = MIN_FRESH_RESULTS,
    val minFreshSpanMs: Long = MIN_FRESH_SPAN_MS,
) {
    init {
        require(minFreshResults > 0) { "At least one fresh result releases a hold, got $minFreshResults" }
        require(minFreshSpanMs >= 0) { "A span is not negative, got $minFreshSpanMs" }
    }

    /**
     * A hold: why, since when, whether the stop still holds, and how far the recovery has come. No progress while the
     * stop holds.
     *
     * @param lastFreshSequenceNumber the frame of the latest result counted; null before the first.
     */
    data class Held<C>(
        val cause: C,
        val sinceMs: Long,
        val stopping: Boolean,
        val fresh: Int,
        val firstFreshReceivedAtMs: Long?,
        val lastFreshReceivedAtMs: Long?,
        val receiptFallbacks: Int,
        val lastFreshSequenceNumber: Long?,
    ) {
        val freshSpanMs: Long get() = if (firstFreshReceivedAtMs == null || lastFreshReceivedAtMs == null) 0 else lastFreshReceivedAtMs - firstFreshReceivedAtMs
    }

    /** A usable result an input brought: its frame, and that frame's receipt, null when the source did not record it. */
    data class Result(val sequenceNumber: Long, val receivedAtMs: Long?)

    /** What ended a hold, the result that ended it counted in: the evidence the trace keeps once the hold is gone. */
    data class Release<C>(
        val cause: C,
        val sinceMs: Long,
        val fresh: Int,
        val firstFreshReceivedAtMs: Long,
        val lastFreshReceivedAtMs: Long,
        val receiptFallbacks: Int,
        val sequenceNumber: Long,
    ) {
        val freshSpanMs: Long get() = lastFreshReceivedAtMs - firstFreshReceivedAtMs
    }

    /** What one step did. */
    sealed interface Change<out C> {
        data object None : Change<Nothing>

        /** A stop began here: a hold started, or its cause changed. */
        data object StopBegan : Change<Nothing>

        data class Released<C>(val release: Release<C>) : Change<C>
    }

    var held: Held<C>? = null
        private set

    /** One input at [nowMs]: the stop cause it leaves (null: none) and the usable result it brought, if any. */
    fun step(nowMs: Long, cause: C?, result: Result?): Change<C> {
        val current = held
        if (cause != null) {
            if (current != null && current.stopping && current.cause == cause) return Change.None
            held = Held(cause, nowMs, stopping = true, fresh = 0, firstFreshReceivedAtMs = null, lastFreshReceivedAtMs = null, receiptFallbacks = 0, lastFreshSequenceNumber = null)
            return Change.StopBegan
        }
        if (current == null) return Change.None
        val recovering = if (current.stopping) current.copy(stopping = false) else current
        held = recovering
        if (result == null) return Change.None
        val receivedAtMs = result.receivedAtMs ?: nowMs
        if (receivedAtMs <= recovering.sinceMs) return Change.None
        val counted = recovering.copy(
            fresh = recovering.fresh + 1,
            firstFreshReceivedAtMs = recovering.firstFreshReceivedAtMs ?: receivedAtMs,
            lastFreshReceivedAtMs = receivedAtMs,
            receiptFallbacks = recovering.receiptFallbacks + if (result.receivedAtMs == null) 1 else 0,
            lastFreshSequenceNumber = result.sequenceNumber,
        )
        if (counted.fresh >= minFreshResults && counted.freshSpanMs >= minFreshSpanMs) {
            held = null
            return Change.Released(
                Release(
                    cause = counted.cause,
                    sinceMs = counted.sinceMs,
                    fresh = counted.fresh,
                    firstFreshReceivedAtMs = checkNotNull(counted.firstFreshReceivedAtMs),
                    lastFreshReceivedAtMs = receivedAtMs,
                    receiptFallbacks = counted.receiptFallbacks,
                    sequenceNumber = result.sequenceNumber,
                ),
            )
        }
        held = counted
        return Change.None
    }

    /** The session ended, or a new one starts: nothing is held over. */
    fun clear() {
        held = null
    }

    companion object {
        /**
         * Together with [MIN_FRESH_SPAN_MS], chosen from one set of SM8850 sessions, where false prompts followed a
         * recovered view for about 130 ms, and from the frame-quality debounce's scale (6 frames, about 280 ms there).
         * Not calibrated.
         */
        const val MIN_FRESH_RESULTS = 3
        const val MIN_FRESH_SPAN_MS = 500L
    }
}
