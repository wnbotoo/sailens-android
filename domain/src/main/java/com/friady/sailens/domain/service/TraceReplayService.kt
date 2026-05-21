package com.friady.sailens.domain.service

import com.friady.sailens.domain.model.trace.TraceSessionDescriptor

interface TraceReplayService {
    fun listSessions(): List<TraceSessionDescriptor>
    fun readSessionLines(sessionId: String): List<String>
}

