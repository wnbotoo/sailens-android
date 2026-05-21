package com.friady.sailens.domain.usecase.trace

import com.friady.sailens.domain.model.trace.TraceSessionDescriptor
import com.friady.sailens.domain.service.TraceReplayService

class FakeTraceReplayService(
    private val sessions: List<TraceSessionDescriptor> = emptyList(),
    private val linesBySessionId: Map<String, List<String>> = emptyMap(),
) : TraceReplayService {
    override fun listSessions(): List<TraceSessionDescriptor> = sessions

    override fun readSessionLines(sessionId: String): List<String> = linesBySessionId[sessionId].orEmpty()
}

