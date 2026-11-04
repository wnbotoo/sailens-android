package com.sailens.guidance.trace

import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary

/**
 * Orders the records that come from the main thread against the opening and closing of a trace session,
 * and numbers them: every alert record lands after its session's `session_start` and before its
 * `session_summary`, which carries how many there were.
 *
 * It has a lock of its own and never takes the trace service's, which is held while the queue is encoded
 * and written to disk: the main thread must not wait for a disk. Inside the lock there are only the
 * attached session, the counter and [CountedTraceQueue.offerCounted]; no encoding, no disk and no trim.
 * A caller trims after the call returns.
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

    /** Queues the session's opening and attaches it: what is recorded from now on belongs to it, numbered from 1. */
    fun attach(metadata: SessionTraceMetadata) = synchronized(lock) {
        queue.offerCounted(TraceQueueEntry.SessionStart(metadata))
        sessionId = metadata.sessionId
        alerts = 0
    }

    /** Detaches first, then queues the closing with the count, so nothing recorded later can follow it. */
    fun detach(summary: SessionTraceSummary) = synchronized(lock) {
        sessionId = null
        queue.offerCounted(TraceQueueEntry.SessionSummary(summary.copy(guidanceAlertCount = alerts)))
    }
}
