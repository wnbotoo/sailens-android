package com.friady.sailens.domain.usecase.trace

import com.friady.sailens.domain.model.trace.TraceSessionDescriptor
import com.friady.sailens.domain.service.TraceReplayService

class ListTraceSessionsUseCase(
    private val traceReplayService: TraceReplayService,
) {
    operator fun invoke(): List<TraceSessionDescriptor> = traceReplayService.listSessions()
}

