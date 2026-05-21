package com.friady.sailens.domain.service

import com.friady.sailens.domain.model.trace.FrameTrace
import com.friady.sailens.domain.model.trace.SessionTraceMetadata
import com.friady.sailens.domain.model.trace.SessionTraceSummary

interface TraceService {
    fun startSession(metadata: SessionTraceMetadata)
    fun recordFrame(frameTrace: FrameTrace)
    fun recordError(sessionId: String, stage: String, throwable: Throwable)
    fun finishSession(summary: SessionTraceSummary)
}

