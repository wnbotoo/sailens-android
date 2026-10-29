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
    /**
     * M3o camera geometry for this frame: "ok" / "degraded" / "unavailable"; "none" in traces
     * written before it existed. Issues are [com.sailens.guidance.geometry.GeometryIssue] names,
     * lower case. Nothing decides on these yet; they are here so recordings show what the
     * geometry would have been.
     */
    val geometryQuality: String = "none",
    val geometryIssues: List<String> = emptyList(),
    /** "camera_realtime" / "source_receipt" / "none". */
    val geometryTimeBasis: String = "none",
    val gravityAgeMs: Double? = null,
    /** Camera pitch below the horizon and roll (positive: gravity leans to image right), degrees. */
    val pitchDownDeg: Double? = null,
    val rollDeg: Double? = null,
    /** "calibration" / "focal_length_estimate" / "none". */
    val intrinsicsSource: String = "none",
    /** Horizontal focal length in upright analysis-image pixels. */
    val focalPx: Double? = null,
    /** Device heading (not walking direction) and yaw rate, positive turning right. */
    val headingDeg: Double? = null,
    val yawRateDegPerS: Double? = null,
    /** "ok" / "degraded" / "unavailable" / "none". */
    val orientationQuality: String = "none",
    /** The phone height the geometry assumed, metres, and where it came from ("default_seed" / "preset" / "calibrated" / "none"). */
    val phoneHeightM: Double? = null,
    val heightSource: String = "none",
    /** Whether the ground-contact distance (M3a) was allowed to change levels in this session. */
    val groundContactEnabled: Boolean = false,
    /** M3a: per tracked obstacle, today's level, the ground-contact estimate and the level used. */
    val obstacleDistances: List<ObstacleDistanceTrace> = emptyList(),
)

/**
 * One tracked obstacle's distance evidence (M3a). Levels are `DistanceLevel` names, lower case;
 * [levelNew] is the level of [lowerBoundM], null without an interval. [upperBoundM] is null when
 * unbounded or without an interval. [contactValidity] is a `ContactValidity` name, lower case.
 */
data class ObstacleDistanceTrace(
    val category: String,
    val zone: String,
    val contactValidity: String,
    val lowerBoundM: Double? = null,
    val pointM: Double? = null,
    val upperBoundM: Double? = null,
    val levelToday: String,
    val levelNew: String? = null,
    val levelUsed: String,
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
 * so it lands after its frame's record; join the two on [sourceSequenceNumber] (the frame record is
 * always written for a frame that offered a prompt, whatever the trace sampling).
 *
 * Invariants, enforced on construction so the writer and the parser share them:
 * - exactly one of [deliveredAt] and [revokedAt] is set; both are wall-clock milliseconds, the same
 *   clock as [FrameTrace.pipelineCompletedAt];
 * - delivered: [deliveredVia] names at least one channel from [PromptDeliveryChannels], and there is
 *   no [revokeReason];
 * - revoked: [revokeReason] is one of [PromptRevokeReasons], and [deliveredVia] is empty.
 *
 * [deliveredVia] is the evidence of what the user got — heard, felt or only shown. The output
 * settings are context, not evidence: with speech and haptics both on, a prompt can still reach the
 * user by vibration alone while the speech engine is starting.
 *
 * "Delivered" means the channel accepted the prompt. Spoken prompts can still be cut short later by
 * a strictly higher-priority one; that is not recorded here.
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
    /** Why it was revoked; null when delivered. One of [PromptRevokeReasons]. */
    val revokeReason: String? = null,
    /** The channels that accepted it, from [PromptDeliveryChannels]; empty when revoked. */
    val deliveredVia: List<String> = emptyList(),
    /** The output settings at the time. Context only; [deliveredVia] says what actually happened. */
    val speechEnabled: Boolean,
    val screenReaderActive: Boolean,
    val hapticsEnabled: Boolean,
) {
    init {
        require((deliveredAt == null) != (revokedAt == null)) {
            "A prompt outcome is either delivered or revoked: deliveredAt=$deliveredAt revokedAt=$revokedAt"
        }
        if (deliveredAt != null) {
            require(revokeReason == null) { "A delivered prompt has no revoke reason, got '$revokeReason'" }
            require(deliveredVia.isNotEmpty()) { "A delivered prompt names the channels that accepted it" }
            require(deliveredVia.all { it in PromptDeliveryChannels.all }) {
                "Unknown delivery channel in $deliveredVia"
            }
        } else {
            require(revokeReason in PromptRevokeReasons.all) { "Unknown revoke reason '$revokeReason'" }
            require(deliveredVia.isEmpty()) { "A revoked prompt reached no channel, got $deliveredVia" }
        }
    }
}

/** The closed set of [PromptOutcomeTrace.revokeReason] values. */
object PromptRevokeReasons {
    /** Not urgent, and a scene description held the floor; offered again later. */
    const val WAITING_FOR_DESCRIPTION = "waiting_for_description"

    /**
     * No enabled output channel accepted it: for example speech busy with an equal or higher
     * priority, the speech engine still starting with no other channel taking it, or the vibration
     * motor refusing a haptics-only prompt.
     */
    const val OUTPUT_REFUSED = "output_refused"

    val all: Set<String> = setOf(WAITING_FOR_DESCRIPTION, OUTPUT_REFUSED)
}

/** The closed set of [PromptOutcomeTrace.deliveredVia] values. */
object PromptDeliveryChannels {
    /** The app's own text-to-speech spoke it. */
    const val SPEECH = "speech"

    /** Handed to the screen reader (TalkBack), which speaks it in the user's own settings. */
    const val SCREEN_READER = "screen_reader"

    /** Its vibration rhythm played. */
    const val HAPTICS = "haptics"

    /** Speech and haptics are both off; the status card is the channel the user chose. */
    const val STATUS_CARD = "status_card"

    /**
     * A covered lens, low light or an unrecognised floor (det-first plan D5): the shell's vision-fault
     * alerts say these states with their own cadence and fallback, so the event itself is not spoken.
     * Taken over, not heard: whether the user heard the alert is tracked there.
     */
    const val VISION_ALERTS = "vision_alerts"

    val all: Set<String> = setOf(SPEECH, SCREEN_READER, HAPTICS, STATUS_CARD, VISION_ALERTS)
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
