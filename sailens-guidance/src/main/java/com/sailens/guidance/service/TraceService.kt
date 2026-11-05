package com.sailens.guidance.service

import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary

interface TraceService {
    fun startSession(metadata: SessionTraceMetadata)
    fun recordFrame(frameTrace: FrameTrace)
    fun recordOverlayRender(
        renderedAt: Long,
        renderMs: Long,
        overlayMode: String,
        bitmapRendered: Boolean,
        sourceSequenceNumber: Long = 0,
        sourcePipelineCompletedAt: Long = 0,
        sourceAgeMs: Long = 0,
    )
    /**
     * What became of the prompt a frame offered; see [PromptOutcomeTrace]. Recorded against the
     * active session (its `sessionId` is filled in here), and dropped when no session is active.
     */
    fun recordPromptOutcome(outcome: PromptOutcomeTrace)
    fun recordError(sessionId: String, stage: String, throwable: Throwable)
    fun finishSession(summary: SessionTraceSummary)

    /**
     * One alert the user was given or offered; see [GuidanceAlertTrace]. Queued with the session's next
     * `seq` while a session is open, dropped when none is. Called on the main thread, so it must never wait
     * for the trace's disk writes.
     */
    fun recordGuidanceAlert(alert: GuidanceAlertTrace) {}

    /**
     * Where Guidance stands for the user, written whenever it or a fact behind it changed; see [SafetyStateTrace].
     * Queued with the session's next `seq` while a session is open. The latest is kept either way, and written again
     * first thing when a session opens. Called on the main thread, so it must never wait for the trace's disk writes.
     */
    fun recordSafetyState(state: SafetyStateTrace) {}
}
