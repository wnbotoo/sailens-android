package com.sailens.guidance.trace

import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
import com.sailens.guidance.service.TraceService

object NoOpTraceService : TraceService {
    override fun startSession(metadata: SessionTraceMetadata) = Unit

    override fun recordFrame(frameTrace: FrameTrace) = Unit

    override fun recordOverlayRender(
        renderedAt: Long,
        renderMs: Long,
        overlayMode: String,
        bitmapRendered: Boolean,
        sourceSequenceNumber: Long,
        sourcePipelineCompletedAt: Long,
        sourceAgeMs: Long,
    ) = Unit

    override fun recordError(sessionId: String, stage: String, throwable: Throwable) = Unit

    override fun finishSession(summary: SessionTraceSummary) = Unit
}
