package com.sailens.guidance.service

import com.sailens.guidance.model.trace.FrameTrace
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
    fun recordError(sessionId: String, stage: String, throwable: Throwable)
    fun finishSession(summary: SessionTraceSummary)
}
