package com.sailens.output

/** What became of an announcement given to [SpeechManager.speak] with `onOutcome`. */
public enum class UtteranceOutcome {
    /** The engine reported `onDone` before the announcement was given up on. */
    SPOKEN,

    /** Dropped, stopped or failed with no `onDone` known at that moment, or none within the window. */
    CUT,

    /**
     * After [CUT]: `onDone` arrived after all, so the sentence was heard. It takes nothing of [CUT] back;
     * it only says the words reached the user. Within the window of [UtteranceSettlement] only.
     */
    HEARD_AFTER_CUT,
}

/**
 * How an utterance's end reaches everything that depends on it. Two facts are kept apart:
 *
 * - **Heard** is the engine's `onDone` from the engine instance the attempt was given to, whenever it
 *   arrives, a flush before it included: the same rule the old `onSpoken` follows and the recorded speech
 *   lifecycle is scored by.
 * - **Holding the speaker** is membership of the [UtteranceLedger], which arbitration reads and which
 *   changes on the main thread only.
 *
 * The engine thread ([engineEnded]) reads the end time and records the end under one lock, then the caller
 * posts [complete]. [complete] runs the steps that must not be split, in order: `onSpoken`, the outcome,
 * the ledger, the owner's cleanup. So once an offer can be accepted because the old sentence no longer
 * holds the speaker, its callers already know.
 *
 * [settleFrame] is what a frame calls first, on the main thread: it reads the frame's time under the same
 * lock and completes every end recorded so far, as if each posted [complete] had happened to run just
 * before this frame. No new ordering, one that was always legal, made certain. Its use is that for a frame
 * at t an utterance has ended exactly when its end time is <= t, however late the engine thread or the main
 * looper got round to the posted task. An end that arrives during the frame waits for the frame's own end,
 * so every offer of one frame sees the same speaker.
 *
 * Outcomes are given at most once per announcement: [UtteranceOutcome.SPOKEN] or [UtteranceOutcome.CUT], and
 * after [UtteranceOutcome.CUT] at most one [UtteranceOutcome.HEARD_AFTER_CUT]. A removal from the ledger
 * with `onDone` already recorded gives no verdict: its [complete] says [UtteranceOutcome.SPOKEN]. Without
 * one it is [UtteranceOutcome.CUT] on the spot, no grace period.
 *
 * Bounded: [capacity] announcements waiting, each for [windowMs] from acceptance. What counts is when the
 * engine ended it, not when [complete] got to run: an `onDone` past the window still calls `onSpoken` and is
 * still in the lifecycle record, but gives no outcome, and one within it does, however late the task.
 *
 * Callbacks must only change their owner's state: they run inside [settleFrame] and [removed], so one that
 * speaks would land in the middle of a frame or of an offer. Their exceptions are swallowed.
 *
 * Main thread, except [engineEnded].
 */
internal class UtteranceSettlement(
    private val ledger: UtteranceLedger,
    private val spokenCallbacks: SpokenCallbacks,
    private val requeueSuffix: String,
    private val nanoClock: () -> Long,
    /** The owner's cleanup once an utterance no longer holds the speaker: audio focus. */
    private val onReleased: (attemptId: String) -> Unit,
    /** An announcement got no outcome within [windowMs]: the callback the engine owed never came. */
    private val onTimedOut: (attemptId: String) -> Unit = {},
    private val capacity: Int = DEFAULT_CAPACITY,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private class Ended(val generation: Long, val heard: Boolean, val atMs: Long)

    /** Ends recorded and not completed yet. Everything touching it holds its lock. */
    private val ended = LinkedHashMap<String, Ended>()

    private class Waiting(
        val onOutcome: (UtteranceOutcome) -> Unit,
        val acceptedAtMs: Long,
        /** The engine instance of each attempt: `onDone` counts only from the one it was given to. */
        val generations: MutableMap<String, Long>,
        /** The attempt whose end or removal is the announcement's. An older one's stop is not. */
        var currentAttempt: String,
        var cut: Boolean = false,
    )

    /** By logical id: no outcome yet, or [UtteranceOutcome.CUT] and still within the window. */
    private val waiting = LinkedHashMap<String, Waiting>()

    /**
     * The engine's end of [attemptId], on its own thread.
     *
     * @return when it ended, read under the lock [settleFrame] reads its time under: every end is either
     *   before a frame and settled by it, or after it.
     */
    fun engineEnded(attemptId: String, generation: Long, heard: Boolean): Long = synchronized(ended) {
        val at = nanoClock()
        val earlier = ended[attemptId]
        ended[attemptId] = Ended(generation, heard || earlier?.heard == true, at / NANOS_PER_MS)
        at
    }

    /** Starts a frame. @return its time, the `atMs` of all its offers. */
    fun settleFrame(): Long {
        val (atNanos, due) = synchronized(ended) { nanoClock() to ended.keys.toList() }
        due.forEach(::complete)
        val atMs = atNanos / NANOS_PER_MS
        expire(atMs)
        return atMs
    }

    /** The posted half of [engineEnded]. A no-op when a frame has already settled it. */
    fun complete(attemptId: String) {
        val end = synchronized(ended) { ended.remove(attemptId) } ?: return
        if (end.heard) spokenCallbacks.done(attemptId)
        // As of the engine's end: with no frame or offer since, nothing has looked at the window yet.
        expire(end.atMs)
        val logicalId = attemptId.substringBefore(requeueSuffix)
        val entry = waiting[logicalId]
        if (entry != null) {
            when {
                end.heard && entry.generations[attemptId] == end.generation -> {
                    waiting.remove(logicalId)
                    deliver(entry, if (entry.cut) UtteranceOutcome.HEARD_AFTER_CUT else UtteranceOutcome.SPOKEN)
                }
                // Evidence from another engine counts for nothing, as in the lifecycle's own scoring.
                end.heard -> Unit
                attemptId == entry.currentAttempt -> cut(entry)
            }
        }
        ledger.finished(attemptId)
        onReleased(attemptId)
    }

    /**
     * [entry] starts now, flushing what was on the speaker: the end of an accepted offer. [recordDropped] is
     * the caller's record of what the flush took, made before any of it is told it was cut.
     */
    fun flushIn(
        entry: UtteranceLedger.Entry,
        generation: Long,
        atMs: Long,
        onOutcome: ((UtteranceOutcome) -> Unit)?,
        recordDropped: (List<UtteranceLedger.Entry>) -> Unit,
    ) {
        onOutcome?.let { accepted(entry.id, generation, atMs, it) }
        val dropped = ledger.flushAndAdd(entry)
        recordDropped(dropped)
        removed(dropped)
    }

    private fun accepted(attemptId: String, generation: Long, atMs: Long, onOutcome: (UtteranceOutcome) -> Unit) {
        expire(atMs)
        waiting.remove(attemptId)
        waiting[attemptId] = Waiting(onOutcome, atMs, mutableMapOf(attemptId to generation), attemptId)
        while (waiting.size > capacity) {
            val oldest = waiting.keys.first()
            val entry = waiting.getValue(oldest)
            waiting.remove(oldest)
            if (!entry.cut) deliver(entry, UtteranceOutcome.CUT)
        }
    }

    /** About to hand [entry] back to the engine: its end is now the announcement's. */
    fun handingBack(entry: UtteranceLedger.Entry, generation: Long) {
        val waited = waiting[entry.logicalId] ?: return
        waited.generations[entry.id] = generation
        waited.currentAttempt = entry.id
    }

    /**
     * [entries] left the ledger without having ended there. Call after everything the removal itself
     * records, and after any new sentence is on the ledger: this delivers [UtteranceOutcome.CUT].
     */
    fun removed(entries: List<UtteranceLedger.Entry>) {
        for (removed in entries) {
            val entry = waiting[removed.logicalId] ?: continue
            if (removed.id == entry.currentAttempt && !heardRecorded(entry)) cut(entry)
        }
    }

    /** The engine of [generation] is gone: what it was speaking will not end there. */
    fun generationEnded(generation: Long) {
        for (entry in waiting.values.toList()) {
            if (entry.generations[entry.currentAttempt] == generation && !heardRecorded(entry)) cut(entry)
        }
    }

    /** Everything is over: no ends are coming and no posted task will run. */
    fun clear() {
        synchronized(ended) { ended.clear() }
        val open = waiting.values.filter { !it.cut }
        waiting.clear()
        open.forEach { deliver(it, UtteranceOutcome.CUT) }
    }

    private fun heardRecorded(entry: Waiting): Boolean = synchronized(ended) {
        entry.generations.any { (attemptId, generation) ->
            ended[attemptId]?.let { it.heard && it.generation == generation } == true
        }
    }

    private fun heardWithinWindow(entry: Waiting): Boolean = synchronized(ended) {
        entry.generations.any { (attemptId, generation) ->
            ended[attemptId]?.let { it.heard && it.generation == generation && it.atMs - entry.acceptedAtMs < windowMs } == true
        }
    }

    private fun expire(nowMs: Long) {
        for ((logicalId, entry) in waiting.entries.toList()) {
            // An onDone within the window is recorded and its task on the way: that task gives the outcome.
            if (nowMs - entry.acceptedAtMs < windowMs || heardWithinWindow(entry)) continue
            waiting.remove(logicalId)
            if (!entry.cut) {
                onTimedOut(entry.currentAttempt)
                deliver(entry, UtteranceOutcome.CUT)
            }
        }
    }

    private fun cut(entry: Waiting) {
        if (entry.cut) return
        entry.cut = true
        deliver(entry, UtteranceOutcome.CUT)
    }

    private fun deliver(entry: Waiting, outcome: UtteranceOutcome) {
        try {
            entry.onOutcome(outcome)
        } catch (e: RuntimeException) {
            // A caller's bookkeeping must never reach the speech path.
        }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 16

        /** Above the longest prompt at the slowest speech rate (about 6.3 s). */
        const val DEFAULT_WINDOW_MS: Long = 8_000

        private const val NANOS_PER_MS = 1_000_000L
    }
}

/** Why [announcement][this] may not start at [nowMs] given what holds the speaker; null if it may. */
internal fun Announcement.refusalAt(nowMs: Long, ledger: UtteranceLedger): String? {
    if (isExpired(nowMs)) return UtteranceReasons.EXPIRED
    val speakingPriority = ledger.highestLivePriority(nowMs)
    return if (speakingPriority != null && priority <= speakingPriority) UtteranceReasons.OUTRANKED else null
}
