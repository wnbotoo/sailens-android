package com.sailens.guidance.usecase.trace

import com.sailens.guidance.model.trace.TraceReplayReport

class LoadLatestTraceReplayReportUseCase(
    private val listTraceSessionsUseCase: ListTraceSessionsUseCase,
    private val loadTraceReplayReportUseCase: LoadTraceReplayReportUseCase,
) {
    operator fun invoke(): TraceReplayReport? {
        val latestSession = listTraceSessionsUseCase().firstOrNull() ?: return null
        return loadTraceReplayReportUseCase(latestSession.sessionId)
    }
}

