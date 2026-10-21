package com.sailens.shell.capture

import com.sailens.guidance.trace.capture.RegressionUtteranceRecord
import com.sailens.guidance.trace.regression.RegressionRecorder
import com.sailens.output.SpeechManager
import com.sailens.output.UtteranceEvent
import com.sailens.output.UtteranceLifecycleObserver

/**
 * Carries the speech engine's lifecycle (sailens-output, which knows nothing of Guidance) into the
 * model-regression record (det-first plan D1.3). Debug only: release never observes the lifecycle.
 */
internal class SpeechManagerLifecycleSessions(private val speech: SpeechManager) : SpeechLifecycleSessions {
    override fun begin(sessionId: String) = speech.beginLifecycleSession(sessionId)

    override fun end(sessionId: String) = speech.endLifecycleSession(sessionId)
}

/** Hands each lifecycle event to [recorder], tagged with the session its attempt belongs to. */
internal class UtteranceLifecycleRecording(private val recorder: RegressionRecorder) : UtteranceLifecycleObserver {
    override fun onUtteranceEvent(event: UtteranceEvent) {
        recorder.onUtterance(event.session, event.toRecord())
    }
}

internal fun UtteranceEvent.toRecord(): RegressionUtteranceRecord = RegressionUtteranceRecord(
    event = kind.wireName,
    attemptId = attemptId,
    logicalId = logicalId,
    engineGeneration = engineGeneration,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    reason = reason,
    interrupted = interrupted,
    errorCode = errorCode,
    previousAttemptId = previousAttemptId,
    owner = owner,
    priority = priority,
    textLength = textLength,
)
