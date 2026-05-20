package com.friady.sailens.data.service

import com.friady.sailens.domain.model.trace.FrameTrace
import com.friady.sailens.domain.model.trace.SessionTraceMetadata
import com.friady.sailens.domain.model.trace.SessionTraceSummary
import org.json.JSONArray
import org.json.JSONObject

internal object TraceJsonEncoder {
    fun encodeSessionStart(metadata: SessionTraceMetadata): JSONObject = JSONObject().apply {
        put("type", "session_start")
        put("sessionId", metadata.sessionId)
        put("startedAt", metadata.startedAt)
        put("pipelineMode", metadata.pipelineMode)
        put("targetHardwareProfile", metadata.targetHardwareProfile)
    }

    fun encodeFrame(frameTrace: FrameTrace): JSONObject = JSONObject().apply {
        put("type", "frame")
        put("sessionId", frameTrace.sessionId)
        put("sequenceNumber", frameTrace.sequenceNumber)
        put("frameTimestamp", frameTrace.frameTimestamp)
        put("frameWidth", frameTrace.frameWidth)
        put("frameHeight", frameTrace.frameHeight)
        put("droppedFramesSinceLast", frameTrace.droppedFramesSinceLast)
        put("processFrameMs", frameTrace.processFrameMs)
        put("inferenceMs", frameTrace.inferenceMs)
        put("analyzeSceneMs", frameTrace.analyzeSceneMs)
        put("decideEventsMs", frameTrace.decideEventsMs)
        put("totalPipelineMs", frameTrace.totalPipelineMs)
        put("obstacleCount", frameTrace.obstacleCount)
        put("eventCount", frameTrace.eventCount)
        put("isBlocked", frameTrace.isBlocked)
        put("isNarrowing", frameTrace.isNarrowing)
        put("isRoadDangerous", frameTrace.isRoadDangerous)
        put("messageKeys", JSONArray(frameTrace.messageKeys))
    }

    fun encodeSessionSummary(summary: SessionTraceSummary): JSONObject = JSONObject().apply {
        put("type", "session_summary")
        put("sessionId", summary.sessionId)
        put("startedAt", summary.startedAt)
        put("completedAt", summary.completedAt)
        put("totalFrames", summary.totalFrames)
        put("droppedFrames", summary.droppedFrames)
        put("totalEvents", summary.totalEvents)
        put("blockedFrames", summary.blockedFrames)
        put("dangerousFrames", summary.dangerousFrames)
        put("avgProcessFrameMs", summary.avgProcessFrameMs)
        put("avgTotalPipelineMs", summary.avgTotalPipelineMs)
        put("avgInferenceMs", summary.avgInferenceMs)
        put("p95TotalPipelineMs", summary.p95TotalPipelineMs)
        put("maxTotalPipelineMs", summary.maxTotalPipelineMs)
    }

    fun encodeError(sessionId: String, stage: String, throwable: Throwable): JSONObject = JSONObject().apply {
        put("type", "error")
        put("sessionId", sessionId)
        put("stage", stage)
        put("exception", throwable.javaClass.simpleName)
        put("message", throwable.message ?: "")
        put("stackTrace", throwable.stackTraceToString().take(1000))
    }
}

