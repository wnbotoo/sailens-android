package com.friady.sailens.domain.usecase.trace

import com.friady.sailens.domain.model.trace.TraceReplayReport
import com.friady.sailens.domain.service.TraceReplayService

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

