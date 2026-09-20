package com.sailens.guidance.usecase.trace

import com.sailens.guidance.model.trace.TraceReplayReport
import com.sailens.guidance.service.TraceReplayService

class LoadTraceReplayReportUseCase(
    private val traceReplayService: TraceReplayService,
    private val buildTraceReplayReportUseCase: BuildTraceReplayReportUseCase,
) {
    operator fun invoke(sessionId: String): TraceReplayReport? {
        val lines = traceReplayService.readSessionLines(sessionId)
        if (lines.isEmpty()) return null
        return buildTraceReplayReportUseCase(lines)
    }
}

