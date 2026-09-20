package com.sailens.guidance.service

import com.sailens.guidance.model.trace.TraceSessionDescriptor

interface TraceReplayService {
    fun listSessions(): List<TraceSessionDescriptor>
    fun readSessionLines(sessionId: String): List<String>
}

