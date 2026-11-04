package com.sailens.guidance.trace

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.guidance.model.trace.GuidanceAlertChannels
import com.sailens.guidance.model.trace.GuidanceAlertKinds
import com.sailens.guidance.model.trace.GuidanceAlertResults
import com.sailens.guidance.model.trace.GuidanceAlertSources
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.model.trace.SafetyTraceNames
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
import com.sailens.guidance.model.trace.TraceReplayParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The encoder writes with Android's org.json, which is a stub on the JVM, so the round trip of the safety state, the
 * shell's alerts, and the start's and summary's new fields through the parser is proven here, on a device.
 */
@RunWith(AndroidJUnit4::class)
class SafetyStateEncodingTest {

    @Test
    fun safetyStatesSystemAlertsAndTheirCountsSurviveTheRoundTrip() {
        val metadata = SessionTraceMetadata("s1", 1_700_000_000_000, "default", "test", openedElapsedMs = 5_000, safetyTrace = true)
        val judged = SafetyStateTrace(
            sessionId = "s1",
            seq = 2,
            at = 1_700_000_000_900,
            elapsedMs = 5_900,
            stateSinceElapsedMs = 5_800,
            state = SafetyTraceNames.States.STOP_REQUIRED,
            reasons = listOf("camera_obstructed"),
            phase = SafetyTraceNames.Phases.RUNNING,
            interrupted = false,
            stall = SafetyTraceNames.Stalls.NONE,
            judgement = "unqualified",
            judgementReasons = listOf("camera_obstructed", "ground_unrecognized"),
            outputFaults = listOf("speech_unavailable"),
            expectedNavigationChannel = SafetyTraceNames.Channels.HAPTICS_ONLY,
            cause = SafetyTraceNames.Causes.RESULT,
        )
        val opening = judged.copy(
            seq = 1,
            elapsedMs = 5_000,
            stateSinceElapsedMs = 1_200,
            state = SafetyTraceNames.States.INITIALIZING,
            reasons = emptyList(),
            phase = SafetyTraceNames.Phases.AWAITING_FIRST_RESULT,
            interrupted = true,
            stall = SafetyTraceNames.Stalls.STARTUP_TIMEOUT,
            judgement = null,
            judgementReasons = emptyList(),
            outputFaults = emptyList(),
            expectedNavigationChannel = SafetyTraceNames.Channels.SPEECH,
            cause = SafetyTraceNames.Causes.SESSION_START,
        )
        val alert = GuidanceAlertTrace(
            sessionId = "s1",
            seq = 1,
            at = 1_700_000_000_950,
            elapsedMs = 5_950,
            source = GuidanceAlertSources.SYSTEM,
            kind = GuidanceAlertKinds.INTERRUPTED,
            messageKey = "notice_guidance_interrupted",
            priority = GuidanceAlertTrace.MAX_PRIORITY,
            channel = GuidanceAlertChannels.SPEECH,
            result = GuidanceAlertResults.REQUESTED,
        )
        val summary = SessionTraceSummary(
            sessionId = "s1", startedAt = 1_700_000_000_000, completedAt = 1_700_000_009_000, totalFrames = 0, droppedFrames = 0,
            totalEvents = 0, blockedFrames = 0, dangerousFrames = 0, avgProcessFrameMs = 0.0, avgTotalPipelineMs = 0.0,
            avgInferenceMs = 0.0, p95TotalPipelineMs = 0, maxTotalPipelineMs = 0, closedElapsedMs = 9_000,
            guidanceAlertCount = 1, safetyStateCount = 2,
        )

        val lines = listOf(
            TraceJsonEncoder.encodeSessionStart(metadata),
            TraceJsonEncoder.encodeSafetyState(opening),
            TraceJsonEncoder.encodeSafetyState(judged),
            TraceJsonEncoder.encodeGuidanceAlert(alert),
            TraceJsonEncoder.encodeSessionSummary(summary),
        ).map { it.toString() }
        val parsed = TraceReplayParser.parse(lines)

        assertEquals(metadata, parsed.metadata)
        assertEquals(listOf(opening, judged), parsed.safetyStates)
        assertEquals(listOf(alert), parsed.guidanceAlerts)
        assertEquals(summary.safetyStateCount, parsed.summary?.safetyStateCount)
        assertEquals(summary.guidanceAlertCount, parsed.summary?.guidanceAlertCount)
        assertEquals(summary.closedElapsedMs, parsed.summary?.closedElapsedMs)
    }
}
