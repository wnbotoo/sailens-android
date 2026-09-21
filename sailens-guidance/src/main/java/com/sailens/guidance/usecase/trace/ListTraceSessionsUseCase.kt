package com.sailens.guidance.usecase.trace

import com.sailens.guidance.model.trace.TraceSessionDescriptor
import com.sailens.guidance.service.TraceReplayService

class ListTraceSessionsUseCase(
    private val traceReplayService: TraceReplayService,
) {
    operator fun invoke(): List<TraceSessionDescriptor> = traceReplayService.listSessions()
}

