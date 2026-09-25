package com.sailens.shell.capture

import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
import com.sailens.guidance.service.TraceService

/**
 * Forwards every call to [base] first, then starts or stops field capture with the trace session.
 * Capture can neither fail nor delay a trace call: its hooks are wrapped and only schedule work.
 *
 * Capture therefore exists only where tracing runs: `StartSceneAnalysisUseCase` opens a trace
 * session only when `TraceRuntimeConfig.enabled` (debug builds).
 */
internal class CapturingTraceService(
    private val base: TraceService,
    private val capture: FieldCaptureController,
) : TraceService by base {

    override fun startSession(metadata: SessionTraceMetadata) {
        base.startSession(metadata)
        runCatching { capture.onSessionStarted(metadata.sessionId, metadata.targetHardwareProfile) }
    }

    override fun finishSession(summary: SessionTraceSummary) {
        base.finishSession(summary)
        runCatching { capture.onSessionFinished() }
    }

    // Explicit so the delegation of the other calls is visible in review.
    override fun recordFrame(frameTrace: FrameTrace) = base.recordFrame(frameTrace)
    override fun recordPromptOutcome(outcome: PromptOutcomeTrace) = base.recordPromptOutcome(outcome)
}
