package com.friady.sailens.domain.usecase.trace

import com.friady.sailens.domain.model.trace.TraceReplayReport

class LoadLatestTraceReplayReportUseCase(
    private val listTraceSessionsUseCase: ListTraceSessionsUseCase,
    private val loadTraceReplayReportUseCase: LoadTraceReplayReportUseCase,
) {
    operator fun invoke(): TraceReplayReport? {
        val latestSession = listTraceSessionsUseCase().firstOrNull() ?: return null
        return loadTraceReplayReportUseCase(latestSession.sessionId)
    }
}

