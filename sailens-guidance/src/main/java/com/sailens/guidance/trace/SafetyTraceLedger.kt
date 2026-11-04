package com.sailens.guidance.trace

import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.model.trace.SafetyTraceNames
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary

/**
 * Orders the records that come from the main thread against the opening and closing of a trace session,
 * and numbers them: every alert and safety-state record lands after its session's `session_start` and before its
 * `session_summary`, which carries how many of each there were.
 *
 * The safety state is lasting, so the latest record is kept whether or not a session is attached, and written again
 * as the session's first safety-state record when one is: a state that began before the trace opened (the start
 * request, the stall watch and the flow's start all come before it) is on the session's first lines. An alert is a
 * single event: outside the window it is in no trace, and not kept.
 *
 * It has a lock of its own and never takes the trace service's, which is held while the queue is encoded
 * and written to disk: the main thread must not wait for a disk. Inside the lock there are only the
 * attached session, the kept state, the counters and [CountedTraceQueue.offerCounted]; no encoding, no disk and
 * no trim. A caller trims after the call returns.
 *
 * Locks are always taken in one order: the trace service's ([attach] and [detach] are called holding it),
 * then this one. Nothing holding this one waits for the other, so they cannot deadlock. Waiting here is
 * short but not bounded in real time: it can queue behind an attach, a detach or another producer.
 *
 * Only the records that go through here are ordered by it. Other records (frames, prompt outcomes) go
 * straight to the queue and may still fall between these.
 */
internal class SafetyTraceLedger(private val queue: CountedTraceQueue<TraceQueueEntry>) {
    private val lock = Any()
    private var sessionId: String? = null
    private var alerts = 0L
    private var states = 0L
    private var latestState: SafetyStateTrace? = null

    /**
     * Queues [alert] with the attached session and its next `seq`.
     *
     * @return false, and nothing queued, when no session is attached: an alert outside the window is in no trace.
     */
    fun recordAlert(alert: GuidanceAlertTrace): Boolean = synchronized(lock) {
        val id = sessionId ?: return false
        alerts++
        queue.offerCounted(TraceQueueEntry.GuidanceAlert(alert.copy(sessionId = id, seq = alerts)))
        true
    }

    /**
     * Keeps [state] as the latest, and queues it with the attached session and its next `seq`.
     *
     * @return false when no session is attached: then it is only kept, to open the next session with.
     */
    fun recordState(state: SafetyStateTrace): Boolean = synchronized(lock) {
        latestState = state
        val id = sessionId ?: return false
        states++
        queue.offerCounted(TraceQueueEntry.SafetyState(state.copy(sessionId = id, seq = states)))
        true
    }

    /**
     * Queues the session's opening and attaches it: what is recorded from now on belongs to it, numbered from 1.
     * The kept state, if any, follows the opening at once as `seq` 1, observed at the opening ([openedAt] wall clock,
     * the metadata's `openedElapsedMs`) and with its own `stateSinceElapsedMs`.
     */
    fun attach(metadata: SessionTraceMetadata, openedAt: Long) = synchronized(lock) {
        val openedElapsedMs = checkNotNull(metadata.openedElapsedMs) { "the trace service sets when a session opened" }
        queue.offerCounted(TraceQueueEntry.SessionStart(metadata))
        sessionId = metadata.sessionId
        alerts = 0
        states = 0
        latestState?.let { kept ->
            states++
            queue.offerCounted(
                TraceQueueEntry.SafetyState(
                    kept.copy(
                        sessionId = metadata.sessionId,
                        seq = states,
                        at = openedAt,
                        elapsedMs = openedElapsedMs,
                        cause = SafetyTraceNames.Causes.SESSION_START,
                    ),
                ),
            )
        }
    }

    /** Detaches first, then queues the closing with the counts, so nothing recorded later can follow it. */
    fun detach(summary: SessionTraceSummary) = synchronized(lock) {
        sessionId = null
        queue.offerCounted(TraceQueueEntry.SessionSummary(summary.copy(guidanceAlertCount = alerts, safetyStateCount = states)))
    }
}
