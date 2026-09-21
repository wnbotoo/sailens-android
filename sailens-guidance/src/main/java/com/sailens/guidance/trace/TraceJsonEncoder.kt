package com.sailens.guidance.trace

import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
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
        put("pipelineStartedAt", frameTrace.pipelineStartedAt)
        put("pipelineCompletedAt", frameTrace.pipelineCompletedAt)
        put("cameraFrameIntervalMs", frameTrace.cameraFrameIntervalMs)
        put("pipelineOutputIntervalMs", frameTrace.pipelineOutputIntervalMs)
        put("obstacleCount", frameTrace.obstacleCount)
        put("eventCount", frameTrace.eventCount)
        put("isBlocked", frameTrace.isBlocked)
        put("isNarrowing", frameTrace.isNarrowing)
        put("isRoadDangerous", frameTrace.isRoadDangerous)
        put("navigationPassableRatio", frameTrace.navigationPassableRatio)
        put("navigationPassableDelta", frameTrace.navigationPassableDelta)
        put("roadRatio", frameTrace.roadRatio)
        put("roadRatioDelta", frameTrace.roadRatioDelta)
        put("blockageConfidence", frameTrace.blockageConfidence)
        put("blockageConfidenceDelta", frameTrace.blockageConfidenceDelta)
        put("blockageReason", frameTrace.blockageReason)
        put("verticalReachRatio", frameTrace.verticalReachRatio)
        put("verticalReachDelta", frameTrace.verticalReachDelta)
        put("floodReachRatio", frameTrace.floodReachRatio)
        put("floodReachDelta", frameTrace.floodReachDelta)
        put("widthRetentionP25", frameTrace.widthRetentionP25)
        put("widthRetentionP25Delta", frameTrace.widthRetentionP25Delta)
        put("occludedPassableRatio", frameTrace.occludedPassableRatio)
        put("occludedPassableDelta", frameTrace.occludedPassableDelta)
        put("rawObstacleDetectionCount", frameTrace.rawObstacleDetectionCount)
        put("obstacleCountDelta", frameTrace.obstacleCountDelta)
        put("rawObstacleDetectionCountDelta", frameTrace.rawObstacleDetectionCountDelta)
        put("rawObstacleClassNames", JSONArray(frameTrace.rawObstacleClassNames))
        put("trackedObstacleCategories", JSONArray(frameTrace.trackedObstacleCategories))
        put("roadVehicleSource", frameTrace.roadVehicleSource)
        put("roadVehicleConfidence", frameTrace.roadVehicleConfidence)
        put("roadVehicleReason", frameTrace.roadVehicleReason)
        put("roadVehicleBottomY", frameTrace.roadVehicleBottomY)
        put("roadVehicleCenterBandOverlap", frameTrace.roadVehicleCenterBandOverlap)
        put("roadVehicleAreaRatio", frameTrace.roadVehicleAreaRatio)
        put("dominantClasses", JSONArray(frameTrace.dominantClasses))
        put("dominantClassPercentages", JSONArray(frameTrace.dominantClassPercentages))
        put("messageKeys", JSONArray(frameTrace.messageKeys))
        put("semanticPreprocessMs", frameTrace.semanticPreprocessMs)
        put("semanticInferenceMs", frameTrace.semanticInferenceMs)
        put("semanticOutputReadMs", frameTrace.semanticOutputReadMs)
        put("semanticPostprocessMs", frameTrace.semanticPostprocessMs)
        put("semanticAccelerator", frameTrace.semanticAccelerator)
        put("semanticAcceleratorSelection", frameTrace.semanticAcceleratorSelection)
        put("semanticPreprocessBackend", frameTrace.semanticPreprocessBackend)
        put("semanticPostprocessBackend", frameTrace.semanticPostprocessBackend)
        put("obstaclePreprocessMs", frameTrace.obstaclePreprocessMs)
        put("obstacleInferenceMs", frameTrace.obstacleInferenceMs)
        put("obstacleOutputReadMs", frameTrace.obstacleOutputReadMs)
        put("obstaclePostprocessMs", frameTrace.obstaclePostprocessMs)
        put("obstacleRunKind", frameTrace.obstacleRunKind)
        put("obstacleAccelerator", frameTrace.obstacleAccelerator)
        put("obstacleAcceleratorSelection", frameTrace.obstacleAcceleratorSelection)
        put("obstaclePreprocessBackend", frameTrace.obstaclePreprocessBackend)
        put("obstaclePostprocessBackend", frameTrace.obstaclePostprocessBackend)
    }

    fun encodeOverlayRender(
        sessionId: String,
        renderedAt: Long,
        renderMs: Long,
        overlayMode: String,
        bitmapRendered: Boolean,
        sourceSequenceNumber: Long,
        sourcePipelineCompletedAt: Long,
        sourceAgeMs: Long,
    ): JSONObject = JSONObject().apply {
        put("type", "overlay_render")
        put("sessionId", sessionId)
        put("renderedAt", renderedAt)
        put("renderMs", renderMs)
        put("overlayMode", overlayMode)
        put("bitmapRendered", bitmapRendered)
        put("sourceSequenceNumber", sourceSequenceNumber)
        put("sourcePipelineCompletedAt", sourcePipelineCompletedAt)
        put("sourceAgeMs", sourceAgeMs)
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
