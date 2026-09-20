package com.sailens.guidance.usecase.trace

import com.sailens.guidance.model.trace.TraceSessionDescriptor
import com.sailens.guidance.service.TraceReplayService

class FakeTraceReplayService(
    private val sessions: List<TraceSessionDescriptor> = emptyList(),
    private val linesBySessionId: Map<String, List<String>> = emptyMap(),
) : TraceReplayService {
    override fun listSessions(): List<TraceSessionDescriptor> = sessions

    override fun readSessionLines(sessionId: String): List<String> = linesBySessionId[sessionId].orEmpty()
}

