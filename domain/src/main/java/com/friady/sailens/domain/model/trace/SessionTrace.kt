package com.friady.sailens.domain.model.trace

import kotlin.math.ceil

data class SessionTraceMetadata(
    val sessionId: String,
    val startedAt: Long,
    val pipelineMode: String,
    val targetHardwareProfile: String,
)

data class FrameTrace(
    val sessionId: String,
    val sequenceNumber: Long,
    val frameTimestamp: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val droppedFramesSinceLast: Int,
    val processFrameMs: Long,
    val inferenceMs: Long,
    val analyzeSceneMs: Long,
    val decideEventsMs: Long,
    val totalPipelineMs: Long,
    val pipelineStartedAt: Long = 0,
    val pipelineCompletedAt: Long = 0,
    val cameraFrameIntervalMs: Long = 0,
    val pipelineOutputIntervalMs: Long = 0,
    val obstacleCount: Int,
    val eventCount: Int,
    val isBlocked: Boolean,
    val isNarrowing: Boolean,
    val isRoadDangerous: Boolean,
    val navigationPassableRatio: Double,
    val blockageConfidence: Double,
    val verticalReachRatio: Double,
    val floodReachRatio: Double,
    val widthRetentionP25: Double,
    val messageKeys: List<String>,
    val semanticPreprocessMs: Long = 0,
    val semanticInferenceMs: Long = 0,
    val semanticOutputReadMs: Long = 0,
    val semanticPostprocessMs: Long = 0,
    val instancePreprocessMs: Long = 0,
    val instanceInferenceMs: Long = 0,
    val instanceOutputReadMs: Long = 0,
    val instancePostprocessMs: Long = 0,
)

data class OverlayRenderTrace(
    val sessionId: String,
    val renderedAt: Long,
    val renderMs: Long,
    val overlayMode: String,
    val bitmapRendered: Boolean,
)

data class SessionTraceSummary(
    val sessionId: String,
    val startedAt: Long,
    val completedAt: Long,
    val totalFrames: Int,
    val droppedFrames: Int,
    val totalEvents: Int,
    val blockedFrames: Int,
    val dangerousFrames: Int,
    val avgProcessFrameMs: Double,
    val avgTotalPipelineMs: Double,
    val avgInferenceMs: Double,
    val p95TotalPipelineMs: Long,
    val maxTotalPipelineMs: Long,
)

class SessionTraceAccumulator(
    private val sessionId: String,
    private val startedAt: Long,
) {
    private val totalPipelineTimes = mutableListOf<Long>()
    private var totalFrames = 0
    private var droppedFrames = 0
    private var totalEvents = 0
    private var blockedFrames = 0
    private var dangerousFrames = 0
    private var totalProcessFrameMs = 0L
    private var totalInferenceMs = 0L
    private var totalPipelineMs = 0L
    private var maxTotalPipelineMs = 0L

    fun record(frameTrace: FrameTrace) {
        totalFrames++
        droppedFrames += frameTrace.droppedFramesSinceLast
        totalEvents += frameTrace.eventCount
        if (frameTrace.isBlocked) blockedFrames++
        if (frameTrace.isRoadDangerous) dangerousFrames++

        totalProcessFrameMs += frameTrace.processFrameMs
        totalInferenceMs += frameTrace.inferenceMs
        totalPipelineMs += frameTrace.totalPipelineMs
        maxTotalPipelineMs = maxOf(maxTotalPipelineMs, frameTrace.totalPipelineMs)
        totalPipelineTimes += frameTrace.totalPipelineMs
    }

    fun build(completedAt: Long): SessionTraceSummary {
        val sortedTotals = totalPipelineTimes.sorted()
        val p95Index = if (sortedTotals.isEmpty()) {
            0
        } else {
            ceil(sortedTotals.size * 0.95).toInt().coerceIn(1, sortedTotals.size) - 1
        }

        return SessionTraceSummary(
            sessionId = sessionId,
            startedAt = startedAt,
            completedAt = completedAt,
            totalFrames = totalFrames,
            droppedFrames = droppedFrames,
            totalEvents = totalEvents,
            blockedFrames = blockedFrames,
            dangerousFrames = dangerousFrames,
            avgProcessFrameMs = if (totalFrames > 0) totalProcessFrameMs.toDouble() / totalFrames else 0.0,
            avgTotalPipelineMs = if (totalFrames > 0) totalPipelineMs.toDouble() / totalFrames else 0.0,
            avgInferenceMs = if (totalFrames > 0) totalInferenceMs.toDouble() / totalFrames else 0.0,
            p95TotalPipelineMs = sortedTotals.getOrElse(p95Index) { 0L },
            maxTotalPipelineMs = maxTotalPipelineMs,
        )
    }
}
