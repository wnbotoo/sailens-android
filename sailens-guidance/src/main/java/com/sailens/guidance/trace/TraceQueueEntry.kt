package com.sailens.guidance.trace

import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
import org.json.JSONObject

/** One line of a trace file, waiting in [CountedTraceQueue] until the flush encodes and writes it. */
internal sealed interface TraceQueueEntry {
    fun encode(): JSONObject

    data class SessionStart(
        val metadata: SessionTraceMetadata,
    ) : TraceQueueEntry {
        override fun encode(): JSONObject = TraceJsonEncoder.encodeSessionStart(metadata)
    }

    data class Frame(
        val frameTrace: FrameTrace,
    ) : TraceQueueEntry {
        override fun encode(): JSONObject = TraceJsonEncoder.encodeFrame(frameTrace)
    }

    data class OverlayRender(
        val sessionId: String,
        val renderedAt: Long,
        val renderMs: Long,
        val overlayMode: String,
        val bitmapRendered: Boolean,
        val sourceSequenceNumber: Long,
        val sourcePipelineCompletedAt: Long,
        val sourceAgeMs: Long,
    ) : TraceQueueEntry {
        override fun encode(): JSONObject = TraceJsonEncoder.encodeOverlayRender(
            sessionId = sessionId,
            renderedAt = renderedAt,
            renderMs = renderMs,
            overlayMode = overlayMode,
            bitmapRendered = bitmapRendered,
            sourceSequenceNumber = sourceSequenceNumber,
            sourcePipelineCompletedAt = sourcePipelineCompletedAt,
            sourceAgeMs = sourceAgeMs,
        )
    }

    data class PromptOutcome(
        val outcome: PromptOutcomeTrace,
    ) : TraceQueueEntry {
        override fun encode(): JSONObject = TraceJsonEncoder.encodePromptOutcome(outcome)
    }

    data class GuidanceAlert(
        val alert: GuidanceAlertTrace,
    ) : TraceQueueEntry {
        override fun encode(): JSONObject = TraceJsonEncoder.encodeGuidanceAlert(alert)
    }

    data class Error(
        val sessionId: String,
        val stage: String,
        val throwable: Throwable,
    ) : TraceQueueEntry {
        override fun encode(): JSONObject = TraceJsonEncoder.encodeError(sessionId, stage, throwable)
    }

    data class SessionSummary(
        val summary: SessionTraceSummary,
    ) : TraceQueueEntry {
        override fun encode(): JSONObject = TraceJsonEncoder.encodeSessionSummary(summary)
    }
}
