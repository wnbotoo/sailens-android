package com.sailens.guidance.model.trace

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
    val navigationPassableDelta: Double = 0.0,
    val roadRatio: Double = 0.0,
    val roadRatioDelta: Double = 0.0,
    val blockageConfidence: Double,
    val blockageConfidenceDelta: Double = 0.0,
    val blockageReason: String = "none",
    val verticalReachRatio: Double,
    val verticalReachDelta: Double = 0.0,
    val floodReachRatio: Double,
    val floodReachDelta: Double = 0.0,
    val widthRetentionP25: Double,
    val widthRetentionP25Delta: Double = 0.0,
    val occludedPassableRatio: Double = 0.0,
    val occludedPassableDelta: Double = 0.0,
    /** [com.sailens.guidance.model.analysis.GroundRecognition] 的小写名。 */
    val groundRecognition: String = "recognized",
    val unrecognizedGroundRatio: Double = 0.0,
    val rawObstacleDetectionCount: Int = 0,
    val obstacleCountDelta: Int = 0,
    val rawObstacleDetectionCountDelta: Int = 0,
    val rawObstacleClassNames: List<String> = emptyList(),
    val trackedObstacleCategories: List<String> = emptyList(),
    val roadVehicleSource: String = "none",
    val roadVehicleConfidence: Double = 0.0,
    val roadVehicleReason: String = "none",
    val roadVehicleBottomY: Double = 0.0,
    val roadVehicleCenterBandOverlap: Double = 0.0,
    val roadVehicleAreaRatio: Double = 0.0,
    val messageKeys: List<String>,
    val dominantClasses: List<String> = emptyList(),
    val dominantClassPercentages: List<String> = emptyList(),
    val semanticPreprocessMs: Long = 0,
    val semanticInferenceMs: Long = 0,
    val semanticOutputReadMs: Long = 0,
    val semanticPostprocessMs: Long = 0,
    val semanticAccelerator: String = "unknown",
    val semanticAcceleratorSelection: String = "unknown",
    val semanticPreprocessBackend: String = "unknown",
    val semanticPostprocessBackend: String = "unknown",
    val obstaclePreprocessMs: Long = 0,
    val obstacleInferenceMs: Long = 0,
    val obstacleOutputReadMs: Long = 0,
    val obstaclePostprocessMs: Long = 0,
    /** 本帧 obstacle 槽位是否运行了模型："det" / "none"（旧 trace 无此字段，解析为 "none"） */
    val obstacleRunKind: String = "none",
    val obstacleAccelerator: String = "unknown",
    val obstacleAcceleratorSelection: String = "unknown",
    val obstaclePreprocessBackend: String = "unknown",
    val obstaclePostprocessBackend: String = "unknown",
)

data class OverlayRenderTrace(
    val sessionId: String,
    val renderedAt: Long,
    val renderMs: Long,
    val overlayMode: String,
    val bitmapRendered: Boolean,
    val sourceSequenceNumber: Long = 0,
    val sourcePipelineCompletedAt: Long = 0,
    val sourceAgeMs: Long = 0,
)

/**
 * What became of the one prompt a frame offered the user: delivered, or revoked and why.
 *
 * [FrameTrace.messageKeys] lists the frame's candidates after cooldown, which is not what the user
 * heard: only the first is offered, and it can still be held back for a description or refused by
 * an output that is busy with higher-priority speech. Labelling false alarms against candidates would
 * label prompts nobody received. This record is written by the output side after delivery is decided,
 * so it lands after its frame's record; join the two on [sourceSequenceNumber].
 *
 * Exactly one of [deliveredAt] and [revokedAt] is set. Both are wall-clock milliseconds, the same
 * clock as [FrameTrace.pipelineCompletedAt].
 *
 * "Delivered" means an output channel accepted the prompt. Spoken prompts can still be cut short
 * later by a strictly higher-priority one; that is not recorded here.
 */
data class PromptOutcomeTrace(
    /** Filled in by the trace service from its active session; callers on the output side leave it. */
    val sessionId: String = "",
    /** [com.sailens.guidance.model.scene.SceneEvent.id]. */
    val eventId: String,
    /** The frame that offered it ([FrameTrace.sequenceNumber]). */
    val sourceSequenceNumber: Long,
    val messageKey: String,
    val category: String,
    val priority: String,
    val deliveredAt: Long? = null,
    val revokedAt: Long? = null,
    /** Why it was revoked; null when delivered. See [PromptRevokeReasons]. */
    val revokeReason: String? = null,
    /** The output settings at the time, so a label can tell "heard" from "felt". */
    val speechEnabled: Boolean,
    val screenReaderActive: Boolean,
    val hapticsEnabled: Boolean,
)

/** The closed set of [PromptOutcomeTrace.revokeReason] values. */
object PromptRevokeReasons {
    /** Not urgent, and a scene description held the floor; offered again later. */
    const val WAITING_FOR_DESCRIPTION = "waiting_for_description"

    /** Every enabled output refused it (speech busy with an equal or higher priority). */
    const val OUTPUT_REFUSED = "output_refused"
}

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
    private val totalPipelineHistogram = IntArray(MAX_PIPELINE_MS_BUCKET + 1)
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
        val bucket = frameTrace.totalPipelineMs
            .coerceIn(0L, MAX_PIPELINE_MS_BUCKET.toLong())
            .toInt()
        totalPipelineHistogram[bucket]++
    }

    fun build(completedAt: Long): SessionTraceSummary {
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
            p95TotalPipelineMs = percentileTotalPipelineMs(0.95),
            maxTotalPipelineMs = maxTotalPipelineMs,
        )
    }

    private fun percentileTotalPipelineMs(percentile: Double): Long {
        if (totalFrames == 0) return 0L
        val target = ceil(totalFrames * percentile).toInt().coerceIn(1, totalFrames)
        var seen = 0
        for (pipelineMs in totalPipelineHistogram.indices) {
            seen += totalPipelineHistogram[pipelineMs]
            if (seen >= target) return pipelineMs.toLong()
        }
        return MAX_PIPELINE_MS_BUCKET.toLong()
    }

    private companion object {
        const val MAX_PIPELINE_MS_BUCKET = 10_000
    }
}
