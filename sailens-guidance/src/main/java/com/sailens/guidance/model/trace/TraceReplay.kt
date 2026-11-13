package com.sailens.guidance.model.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class TraceReplaySession(
    val metadata: SessionTraceMetadata?,
    val frames: List<FrameTrace>,
    val overlayRenders: List<OverlayRenderTrace>,
    /** What became of each offered prompt; empty for traces recorded before these were written. */
    val promptOutcomes: List<PromptOutcomeTrace> = emptyList(),
    val errors: List<TraceReplayError>,
    val summary: SessionTraceSummary?,
    /**
     * The alerts the trace's session recorded while it was open, one given just before the opening and recorded after
     * it included, in file order; empty for traces recorded before these were written.
     */
    val guidanceAlerts: List<GuidanceAlertTrace> = emptyList(),
    /** The safety-state records, in file order; empty for traces recorded before these were written. */
    val safetyStates: List<SafetyStateTrace> = emptyList(),
    /** The depth lane's records, in file order; empty without a depth model or for older traces. */
    val depthRuns: List<DepthRunTrace> = emptyList(),
)

data class TraceReplayError(
    val sessionId: String,
    val stage: String,
    val exception: String,
    val message: String,
)

data class TraceReplayReport(
    val sessionId: String,
    val pipelineMode: String?,
    val targetHardwareProfile: String?,
    val totalFrames: Int,
    val droppedFrames: Int,
    val totalObservedFrames: Int,
    val droppedFrameRate: Double,
    val totalEvents: Int,
    val durationMs: Long,
    val cameraInputFps: Double,
    val pipelineOutputFps: Double,
    val pipelineThroughputFps: Double,
    val semanticRunCount: Int,
    val semanticRunFps: Double,
    val obstacleRunCount: Int,
    val obstacleRunFps: Double,
    val overlayRenderCount: Int,
    val maskRenderCount: Int,
    val avgMaskRenderMs: Double,
    val maskRenderFps: Double,
    val avgMaskSourceAgeMs: Double,
    val maxMaskSourceAgeMs: Long,
    val blockedFrames: Int,
    val dangerousFrames: Int,
    val blockedFrameRate: Double,
    val dangerousFrameRate: Double,
    val avgProcessFrameMs: Double,
    val avgInferenceMs: Double,
    val avgAnalyzeSceneMs: Double,
    val avgDecideEventsMs: Double,
    val avgLogicMs: Double,
    val avgSemanticTotalMs: Double,
    val avgSemanticPreprocessMs: Double,
    val avgSemanticInferenceMs: Double,
    val avgSemanticOutputReadMs: Double,
    val avgSemanticPostprocessMs: Double,
    val avgObstacleTotalMs: Double,
    val avgObstaclePreprocessMs: Double,
    val avgObstacleInferenceMs: Double,
    val avgObstacleOutputReadMs: Double,
    val avgObstaclePostprocessMs: Double,
    val semanticAccelerators: List<String>,
    val semanticAcceleratorSelections: List<String>,
    val semanticPreprocessBackends: List<String>,
    val semanticPostprocessBackends: List<String>,
    val obstacleAccelerators: List<String>,
    val obstacleAcceleratorSelections: List<String>,
    val obstaclePreprocessBackends: List<String>,
    val obstaclePostprocessBackends: List<String>,
    val avgTotalPipelineMs: Double,
    val p95TotalPipelineMs: Long,
    val maxTotalPipelineMs: Long,
    val avgNavigationPassableRatio: Double,
    val avgNavigationPassableDelta: Double,
    val maxNavigationPassableDelta: Double,
    val avgRoadRatio: Double,
    val avgRoadRatioDelta: Double,
    val maxRoadRatioDelta: Double,
    val avgBlockageConfidence: Double,
    val avgBlockageConfidenceDelta: Double,
    val maxBlockageConfidenceDelta: Double,
    val avgVerticalReachRatio: Double,
    val avgVerticalReachDelta: Double,
    val maxVerticalReachDelta: Double,
    val avgFloodReachRatio: Double,
    val avgFloodReachDelta: Double,
    val maxFloodReachDelta: Double,
    val avgWidthRetentionP25: Double,
    val avgWidthRetentionP25Delta: Double,
    val maxWidthRetentionP25Delta: Double,
    val avgOccludedPassableRatio: Double,
    val maxOccludedPassableRatio: Double,
    val avgOccludedPassableDelta: Double,
    val maxOccludedPassableDelta: Double,
    val avgRawObstacleDetectionCount: Double,
    val avgRawObstacleDetectionCountDelta: Double,
    val maxRawObstacleDetectionCountDelta: Int,
    val avgObstacleCountDelta: Double,
    val maxObstacleCountDelta: Int,
    val maxDroppedFramesSinceLast: Int,
    val errorCount: Int,
    val dominantClasses: List<String>,
    val dominantClassPercentages: List<String>,
    val rawObstacleClassPercentages: List<String>,
    val trackedObstacleCategoryPercentages: List<String>,
    val roadVehicleSourcePercentages: List<String>,
    val avgRoadVehicleConfidence: Double,
    val roadVehicleReasonPercentages: List<String>,
    val avgRoadVehicleBottomY: Double,
    val avgRoadVehicleCenterBandOverlap: Double,
    val avgRoadVehicleAreaRatio: Double,
    val blockageReasonPercentages: List<String>,
    val uniqueMessageKeys: List<String>,
    /** Traced frames per qualification level; "none" counts frames from before the field existed. */
    val qualificationFrameCounts: Map<String, Int> = emptyMap(),
    /** Alert records per kind: the alerts the session recorded while it was open, not every alert of the run. */
    val guidanceAlertCounts: Map<String, Int> = emptyMap(),
    val guidanceAlertCompleteness: AlertRecordCompleteness = AlertRecordCompleteness.NOT_RECORDED,
    /** Alert records known to be missing; with [AlertRecordCompleteness.INCOMPLETE] and no summary, at least this many. */
    val guidanceAlertsMissing: Long = 0,
    /** How long each safety state held within the trace's window, and how much of it is unknown. */
    val safetyTimeline: SafetyStateTimeline = SafetyStateTimeline.NOT_RECORDED,
    /** The depth lane's runs, from the `depth_run` records. */
    val depthRuns: DepthRunReport = DepthRunReport.NOT_RECORDED,
)

/**
 * How long each safety state held within the trace's window `[openedElapsedMs, closedElapsedMs]`, by elapsed realtime
 * alone (the wall clock may jump). A state counts from its `stateSinceElapsedMs`, or the window's start if it began
 * before, to the next different state's, or the window's end.
 *
 * What the records cannot tell is unknown, never given to a neighbouring state: from a record before a gap in `seq` to
 * the next record's state start (the missing ones may have changed the state any time after the first was observed);
 * after the last record when the session counted more; before the first when the first is missing; and any part of the
 * window no record covers. Unknown time overrides known time where they overlap.
 *
 * @param durationsMs per state label ([SafetyStateTrace.stateLabel]), the time it is known to have held in the window.
 * @param unknownMs the time within the window whose state is not known. When a bound of the window is not in the trace,
 *   the time beyond the record nearest it is not in this either: see [windowStartKnown] and [windowEndKnown].
 * @param windowStartKnown false when neither `session_start` nor the session's first safety state was kept: then
 *   counting starts at the first record kept, and the time before it is unknown, of unknown length.
 * @param windowEndKnown false without a `session_summary` (the process died, or the bound dropped it): then counting
 *   stops at the last record kept, and the time after it is unknown, of unknown length.
 * @param missingRecords the records known to be missing; without the session's count, at least this many.
 * @param endedOnError the trace records the analysis ending on an error: its failure and the alert for it come after
 *   the trace closed, so the timeline stops short of them.
 */
data class SafetyStateTimeline(
    val completeness: SafetyRecordCompleteness,
    val durationsMs: Map<String, Long> = emptyMap(),
    val unknownMs: Long = 0,
    val windowStartKnown: Boolean = false,
    val windowEndKnown: Boolean = false,
    val missingRecords: Long = 0,
    val endedOnError: Boolean = false,
) {
    companion object {
        val NOT_RECORDED = SafetyStateTimeline(SafetyRecordCompleteness.NOT_RECORDED)
    }
}

/** How far a trace's safety-state records are all those of its window. */
enum class SafetyRecordCompleteness {
    /** The trace was written before safety states were recorded: it has no timeline. */
    NOT_RECORDED,

    /** Every `seq` from 1 up to the session's [SessionTraceSummary.safetyStateCount] is there. */
    COMPLETE,

    /** Records are missing: a `seq` gap, a first `seq` above 1, or fewer than the session counted. */
    INCOMPLETE,

    /** None found missing, but there is no count (the process died, or the summary was dropped): the last could be. */
    POSSIBLY_INCOMPLETE,
}

/** How far a trace's alert records are all the alerts its session recorded. */
enum class AlertRecordCompleteness {
    /** The trace was written before alerts were recorded: nothing it kept, not even a frame's qualification, is newer. */
    NOT_RECORDED,

    /** Every `seq` from 1 up to the session's [SessionTraceSummary.guidanceAlertCount] is there: every alert the session recorded. */
    COMPLETE,

    /** Records are missing: a `seq` gap, a first `seq` above 1, or fewer than the session counted. */
    INCOMPLETE,

    /**
     * None found missing, but there is no [SessionTraceSummary.guidanceAlertCount] (the process died, or
     * the summary was dropped): the last ones could be missing.
     */
    POSSIBLY_INCOMPLETE,
}

object TraceReplayParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun parse(lines: List<String>): TraceReplaySession {
        var metadata: SessionTraceMetadata? = null
        var summary: SessionTraceSummary? = null
        val frames = mutableListOf<FrameTrace>()
        val overlayRenders = mutableListOf<OverlayRenderTrace>()
        val promptOutcomes = mutableListOf<PromptOutcomeTrace>()
        val guidanceAlerts = mutableListOf<GuidanceAlertTrace>()
        val safetyStates = mutableListOf<SafetyStateTrace>()
        val depthRuns = mutableListOf<DepthRunTrace>()
        val errors = mutableListOf<TraceReplayError>()

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEachIndexed

            val entry = parseObject(line, index)
            when (val type = entry.requireString("type", index)) {
                SESSION_START_TYPE -> metadata = parseMetadata(entry, index)
                FRAME_TYPE -> frames += parseFrame(entry, index)
                OVERLAY_RENDER_TYPE -> overlayRenders += parseOverlayRender(entry, index)
                PROMPT_OUTCOME_TYPE -> promptOutcomes += parsePromptOutcome(entry, index)
                GUIDANCE_ALERT_TYPE -> guidanceAlerts += parseGuidanceAlert(entry, index)
                SAFETY_STATE_TYPE -> safetyStates += parseSafetyState(entry, index)
                DEPTH_RUN_TYPE -> depthRuns += parseDepthRun(entry, index)
                SESSION_SUMMARY_TYPE -> summary = parseSummary(entry, index)
                ERROR_TYPE -> errors += parseError(entry, index)
                else -> throw IllegalArgumentException("Unsupported trace entry type '$type' at line ${index + 1}")
            }
        }

        return TraceReplaySession(
            metadata = metadata,
            frames = frames,
            overlayRenders = overlayRenders,
            promptOutcomes = promptOutcomes,
            errors = errors,
            summary = summary,
            guidanceAlerts = guidanceAlerts,
            safetyStates = safetyStates,
            depthRuns = depthRuns,
        )
    }

    private fun parseDepthRun(entry: JsonObject, lineIndex: Int): DepthRunTrace {
        fun float(key: String) = entry.optionalDouble(key)?.toFloat()
        val skips = (entry["skipsSinceLast"] as? JsonObject)?.mapValues { (_, v) -> v.jsonPrimitive.longOrNull?.toInt() ?: 0 }.orEmpty()
        val outcome = entry.requireString("outcome", lineIndex)
        require(outcome in DepthRunTrace.ALL) { "Unknown depth_run outcome '$outcome' at line ${lineIndex + 1}" }
        return DepthRunTrace(
            sessionId = entry.requireString("sessionId", lineIndex),
            generation = entry.requireLong("generation", lineIndex),
            runId = entry.requireLong("runId", lineIndex),
            outcome = outcome,
            seq = entry.requireLong("seq", lineIndex),
            atElapsedMs = entry.requireLong("atElapsedMs", lineIndex),
            frameSequence = entry.optionalLong("frameSequence"),
            frameTimeNanos = entry.optionalLong("frameTimeNanos"),
            timeBasis = entry.optionalString("timeBasis"),
            geometryQuality = entry.optionalString("geometryQuality"),
            validity = entry.optionalString("validity"),
            inlierRatio = float("inlierRatio"),
            confidence = float("confidence"),
            planeAgreesWithGravity = entry.optionalBoolean("planeAgreesWithGravity"),
            groundRatio = float("groundRatio"),
            aboveGroundRatio = float("aboveGroundRatio"),
            belowGroundRatio = float("belowGroundRatio"),
            disparityScale = float("disparityScale"),
            disparityShift = float("disparityShift"),
            detail = float("detail"),
            prepareMs = entry.optionalLong("prepareMs"),
            admitWaitMs = entry.optionalLong("admitWaitMs"),
            inferMs = entry.optionalLong("inferMs"),
            outputReadMs = entry.optionalLong("outputReadMs"),
            fitMs = entry.optionalLong("fitMs"),
            fitCpuUs = entry.optionalLong("fitCpuUs"),
            accelerator = entry.optionalString("accelerator"),
            acceleratorSelection = entry.optionalString("acceleratorSelection"),
            preprocessBackend = entry.optionalString("preprocessBackend"),
            postprocessBackend = entry.optionalString("postprocessBackend"),
            modelShape = entry.optionalString("modelShape"),
            skipsSinceLast = skips,
            cancelledRunId = entry.optionalLong("cancelledRunId"),
            cancelledStage = entry.optionalString("cancelledStage"),
            failuresTotal = entry.optionalLong("failuresTotal")?.toInt(),
            finalState = entry.optionalString("finalState"),
        )
    }

    private fun parseObject(line: String, lineIndex: Int): JsonObject {
        return try {
            json.parseToJsonElement(line).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid JSON trace entry at line ${lineIndex + 1}", error)
        }
    }

    private fun parseMetadata(entry: JsonObject, lineIndex: Int) = SessionTraceMetadata(
        sessionId = entry.requireString("sessionId", lineIndex),
        startedAt = entry.requireLong("startedAt", lineIndex),
        pipelineMode = entry.requireString("pipelineMode", lineIndex),
        targetHardwareProfile = entry.requireString("targetHardwareProfile", lineIndex),
        openedElapsedMs = entry.optionalLong("openedElapsedMs"),
        safetyTrace = entry.optionalBoolean("safetyTrace") ?: false,
        holdTrace = entry.optionalBoolean("holdTrace") ?: false,
        depthTrace = entry.optionalBoolean("depthTrace") ?: false,
    )

    private fun parseFrame(entry: JsonObject, lineIndex: Int) = FrameTrace(
        sessionId = entry.requireString("sessionId", lineIndex),
        sequenceNumber = entry.requireLong("sequenceNumber", lineIndex),
        frameTimestamp = entry.requireLong("frameTimestamp", lineIndex),
        frameWidth = entry.requireInt("frameWidth", lineIndex),
        frameHeight = entry.requireInt("frameHeight", lineIndex),
        droppedFramesSinceLast = entry.requireInt("droppedFramesSinceLast", lineIndex),
        processFrameMs = entry.requireLong("processFrameMs", lineIndex),
        inferenceMs = entry.requireLong("inferenceMs", lineIndex),
        analyzeSceneMs = entry.requireLong("analyzeSceneMs", lineIndex),
        decideEventsMs = entry.requireLong("decideEventsMs", lineIndex),
        totalPipelineMs = entry.requireLong("totalPipelineMs", lineIndex),
        pipelineStartedAt = entry.optionalLong("pipelineStartedAt") ?: 0,
        pipelineCompletedAt = entry.optionalLong("pipelineCompletedAt") ?: 0,
        cameraFrameIntervalMs = entry.optionalLong("cameraFrameIntervalMs") ?: 0,
        pipelineOutputIntervalMs = entry.optionalLong("pipelineOutputIntervalMs") ?: 0,
        obstacleCount = entry.requireInt("obstacleCount", lineIndex),
        eventCount = entry.requireInt("eventCount", lineIndex),
        isBlocked = entry.requireBoolean("isBlocked", lineIndex),
        isNarrowing = entry.requireBoolean("isNarrowing", lineIndex),
        isRoadDangerous = entry.requireBoolean("isRoadDangerous", lineIndex),
        navigationPassableRatio = entry.optionalDouble("navigationPassableRatio") ?: 0.0,
        navigationPassableDelta = entry.optionalDouble("navigationPassableDelta") ?: 0.0,
        roadRatio = entry.optionalDouble("roadRatio") ?: 0.0,
        roadRatioDelta = entry.optionalDouble("roadRatioDelta") ?: 0.0,
        blockageConfidence = entry.optionalDouble("blockageConfidence") ?: 0.0,
        blockageConfidenceDelta = entry.optionalDouble("blockageConfidenceDelta") ?: 0.0,
        blockageReason = entry.optionalString("blockageReason") ?: "none",
        verticalReachRatio = entry.optionalDouble("verticalReachRatio") ?: 0.0,
        verticalReachDelta = entry.optionalDouble("verticalReachDelta") ?: 0.0,
        floodReachRatio = entry.optionalDouble("floodReachRatio") ?: 0.0,
        floodReachDelta = entry.optionalDouble("floodReachDelta") ?: 0.0,
        widthRetentionP25 = entry.optionalDouble("widthRetentionP25") ?: 0.0,
        widthRetentionP25Delta = entry.optionalDouble("widthRetentionP25Delta") ?: 0.0,
        occludedPassableRatio = entry.optionalDouble("occludedPassableRatio") ?: 0.0,
        occludedPassableDelta = entry.optionalDouble("occludedPassableDelta") ?: 0.0,
        groundRecognition = entry.optionalString("groundRecognition") ?: "recognized",
        unrecognizedGroundRatio = entry.optionalDouble("unrecognizedGroundRatio") ?: 0.0,
        rawObstacleDetectionCount = entry.optionalLong("rawObstacleDetectionCount")?.toInt() ?: 0,
        obstacleCountDelta = entry.optionalLong("obstacleCountDelta")?.toInt() ?: 0,
        rawObstacleDetectionCountDelta = entry.optionalLong("rawObstacleDetectionCountDelta")?.toInt() ?: 0,
        rawObstacleClassNames = entry.optionalStringArray("rawObstacleClassNames").orEmpty(),
        trackedObstacleCategories = entry.optionalStringArray("trackedObstacleCategories").orEmpty(),
        roadVehicleSource = entry.optionalString("roadVehicleSource") ?: "none",
        roadVehicleConfidence = entry.optionalDouble("roadVehicleConfidence") ?: 0.0,
        roadVehicleReason = entry.optionalString("roadVehicleReason") ?: "none",
        roadVehicleBottomY = entry.optionalDouble("roadVehicleBottomY") ?: 0.0,
        roadVehicleCenterBandOverlap = entry.optionalDouble("roadVehicleCenterBandOverlap") ?: 0.0,
        roadVehicleAreaRatio = entry.optionalDouble("roadVehicleAreaRatio") ?: 0.0,
        messageKeys = entry.requireStringArray("messageKeys", lineIndex),
        dominantClasses = entry.optionalStringArray("dominantClasses").orEmpty(),
        dominantClassPercentages = entry.optionalStringArray("dominantClassPercentages").orEmpty(),
        semanticPreprocessMs = entry.optionalLong("semanticPreprocessMs") ?: 0,
        semanticInferenceMs = entry.optionalLong("semanticInferenceMs") ?: 0,
        semanticOutputReadMs = entry.optionalLong("semanticOutputReadMs") ?: 0,
        semanticPostprocessMs = entry.optionalLong("semanticPostprocessMs") ?: 0,
        semanticAccelerator = entry.optionalString("semanticAccelerator") ?: "unknown",
        semanticAcceleratorSelection = entry.optionalString("semanticAcceleratorSelection") ?: "unknown",
        semanticPreprocessBackend = entry.optionalString("semanticPreprocessBackend") ?: "unknown",
        semanticPostprocessBackend = entry.optionalString("semanticPostprocessBackend") ?: "unknown",
        obstaclePreprocessMs = entry.optionalLong("obstaclePreprocessMs") ?: 0,
        obstacleInferenceMs = entry.optionalLong("obstacleInferenceMs") ?: 0,
        obstacleOutputReadMs = entry.optionalLong("obstacleOutputReadMs") ?: 0,
        obstaclePostprocessMs = entry.optionalLong("obstaclePostprocessMs") ?: 0,
        obstacleRunKind = entry.optionalString("obstacleRunKind") ?: "none",
        obstacleAccelerator = entry.optionalString("obstacleAccelerator") ?: "unknown",
        obstacleAcceleratorSelection = entry.optionalString("obstacleAcceleratorSelection") ?: "unknown",
        obstaclePreprocessBackend = entry.optionalString("obstaclePreprocessBackend") ?: "unknown",
        obstaclePostprocessBackend = entry.optionalString("obstaclePostprocessBackend") ?: "unknown",
        geometryQuality = entry.optionalString("geometryQuality") ?: "none",
        geometryIssues = entry.optionalStringArray("geometryIssues").orEmpty(),
        geometryTimeBasis = entry.optionalString("geometryTimeBasis") ?: "none",
        gravityAgeMs = entry.optionalDouble("gravityAgeMs"),
        pitchDownDeg = entry.optionalDouble("pitchDownDeg"),
        rollDeg = entry.optionalDouble("rollDeg"),
        intrinsicsSource = entry.optionalString("intrinsicsSource") ?: "none",
        focalPx = entry.optionalDouble("focalPx"),
        headingDeg = entry.optionalDouble("headingDeg"),
        yawRateDegPerS = entry.optionalDouble("yawRateDegPerS"),
        orientationQuality = entry.optionalString("orientationQuality") ?: "none",
        phoneHeightM = entry.optionalDouble("phoneHeightM"),
        heightSource = entry.optionalString("heightSource") ?: "none",
        groundContactEnabled = entry.optionalBoolean("groundContactEnabled") ?: false,
        obstacleDistances = (entry["obstacleDistances"] as? JsonArray).orEmpty().map { item ->
            val distance = item.jsonObject
            ObstacleDistanceTrace(
                category = distance.requireString("category", lineIndex),
                zone = distance.requireString("zone", lineIndex),
                contactValidity = distance.requireString("contactValidity", lineIndex),
                lowerBoundM = distance.optionalDouble("lowerBoundM"),
                pointM = distance.optionalDouble("pointM"),
                upperBoundM = distance.optionalDouble("upperBoundM"),
                levelToday = distance.requireString("levelToday", lineIndex),
                levelNew = distance.optionalString("levelNew"),
                levelUsed = distance.requireString("levelUsed", lineIndex),
            )
        },
        frameQuality = entry.optionalString("frameQuality"),
        frameQualityClassified = entry.optionalString("frameQualityClassified"),
        qualification = entry.optionalString("qualification"),
        qualificationReasons = entry.optionalStringArray("qualificationReasons").orEmpty(),
        capabilities = (entry["capabilities"] as? JsonObject).orEmpty().mapValues { (_, value) ->
            val capability = value.jsonObject
            CapabilityTrace(
                status = capability.requireString("status", lineIndex),
                issues = capability.optionalStringArray("issues").orEmpty(),
            )
        },
        receiptToDecisionMs = entry.optionalDouble("receiptToDecisionMs"),
        receivedElapsedMs = entry.optionalLong("receivedElapsedMs"),
        depthRunId = entry.optionalLong("depthRunId"),
        depthAgeMs = entry.optionalLong("depthAgeMs"),
    )

    private fun parseOverlayRender(entry: JsonObject, lineIndex: Int) = OverlayRenderTrace(
        sessionId = entry.requireString("sessionId", lineIndex),
        renderedAt = entry.requireLong("renderedAt", lineIndex),
        renderMs = entry.requireLong("renderMs", lineIndex),
        overlayMode = entry.optionalString("overlayMode") ?: "unknown",
        bitmapRendered = entry.requireBoolean("bitmapRendered", lineIndex),
        sourceSequenceNumber = entry.optionalLong("sourceSequenceNumber") ?: 0,
        sourcePipelineCompletedAt = entry.optionalLong("sourcePipelineCompletedAt") ?: 0,
        sourceAgeMs = entry.optionalLong("sourceAgeMs") ?: 0,
    )

    /** The invariants live in [PromptOutcomeTrace] itself; this only adds the line number. */
    private fun parsePromptOutcome(entry: JsonObject, lineIndex: Int): PromptOutcomeTrace {
        val sessionId = entry.requireString("sessionId", lineIndex)
        val eventId = entry.requireString("eventId", lineIndex)
        val sourceSequenceNumber = entry.requireLong("sourceSequenceNumber", lineIndex)
        val messageKey = entry.requireString("messageKey", lineIndex)
        val category = entry.requireString("category", lineIndex)
        val priority = entry.requireString("priority", lineIndex)
        val speechEnabled = entry.requireBoolean("speechEnabled", lineIndex)
        val screenReaderActive = entry.requireBoolean("screenReaderActive", lineIndex)
        val hapticsEnabled = entry.requireBoolean("hapticsEnabled", lineIndex)
        return try {
            PromptOutcomeTrace(
                sessionId = sessionId,
                eventId = eventId,
                sourceSequenceNumber = sourceSequenceNumber,
                messageKey = messageKey,
                category = category,
                priority = priority,
                deliveredAt = entry.optionalLong("deliveredAt"),
                revokedAt = entry.optionalLong("revokedAt"),
                revokeReason = entry.optionalString("revokeReason"),
                deliveredVia = entry.optionalStringArray("deliveredVia").orEmpty(),
                speechEnabled = speechEnabled,
                screenReaderActive = screenReaderActive,
                hapticsEnabled = hapticsEnabled,
            )
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid prompt_outcome at line ${lineIndex + 1}: ${error.message}", error)
        }
    }

    /** The invariants live in [GuidanceAlertTrace] itself; this only adds the line number. */
    private fun parseGuidanceAlert(entry: JsonObject, lineIndex: Int): GuidanceAlertTrace {
        val sessionId = entry.requireString("sessionId", lineIndex)
        val seq = entry.requireLong("seq", lineIndex)
        val at = entry.requireLong("at", lineIndex)
        val elapsedMs = entry.requireLong("elapsedMs", lineIndex)
        val source = entry.requireString("source", lineIndex)
        val kind = entry.requireString("kind", lineIndex)
        val priority = entry.requireString("priority", lineIndex)
        val channel = entry.requireString("channel", lineIndex)
        val result = entry.requireString("result", lineIndex)
        return try {
            GuidanceAlertTrace(
                sessionId = sessionId,
                seq = seq,
                at = at,
                elapsedMs = elapsedMs,
                source = source,
                kind = kind,
                messageKey = entry.optionalString("messageKey"),
                haptic = entry.optionalString("haptic"),
                priority = priority,
                channel = channel,
                result = result,
            )
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid guidance_alert at line ${lineIndex + 1}: ${error.message}", error)
        }
    }

    /** The invariants live in [SafetyStateTrace] itself; this only adds the line number. */
    private fun parseSafetyState(entry: JsonObject, lineIndex: Int): SafetyStateTrace {
        val sessionId = entry.requireString("sessionId", lineIndex)
        val seq = entry.requireLong("seq", lineIndex)
        val at = entry.requireLong("at", lineIndex)
        val elapsedMs = entry.requireLong("elapsedMs", lineIndex)
        val stateSinceElapsedMs = entry.requireLong("stateSinceElapsedMs", lineIndex)
        val state = entry.requireString("state", lineIndex)
        val phase = entry.requireString("phase", lineIndex)
        val interrupted = entry.requireBoolean("interrupted", lineIndex)
        val stall = entry.requireString("stall", lineIndex)
        val channel = entry.requireString("expectedNavigationChannel", lineIndex)
        val cause = entry.requireString("cause", lineIndex)
        return try {
            SafetyStateTrace(
                sessionId = sessionId,
                seq = seq,
                at = at,
                elapsedMs = elapsedMs,
                stateSinceElapsedMs = stateSinceElapsedMs,
                state = state,
                reasons = entry.optionalStringArray("reasons").orEmpty(),
                phase = phase,
                interrupted = interrupted,
                stall = stall,
                judgement = entry.optionalString("judgement"),
                judgementReasons = entry.optionalStringArray("judgementReasons").orEmpty(),
                outputFaults = entry.optionalStringArray("outputFaults").orEmpty(),
                expectedNavigationChannel = channel,
                cause = cause,
                hold = (entry["hold"] as? JsonObject)?.let { hold ->
                    HoldTrace(
                        cause = hold.requireString("cause", lineIndex),
                        sinceElapsedMs = hold.requireLong("sinceElapsedMs", lineIndex),
                        stopping = hold.requireBoolean("stopping", lineIndex),
                        freshResults = hold.requireLong("freshResults", lineIndex).toInt(),
                        freshSpanMs = hold.requireLong("freshSpanMs", lineIndex),
                        firstFreshReceivedElapsedMs = hold.optionalLong("firstFreshReceivedElapsedMs"),
                        lastFreshReceivedElapsedMs = hold.optionalLong("lastFreshReceivedElapsedMs"),
                        receiptFallbacks = hold.optionalLong("receiptFallbacks")?.toInt() ?: 0,
                        lastFreshSequenceNumber = hold.optionalLong("lastFreshSequenceNumber"),
                    )
                },
                holdReleased = (entry["holdReleased"] as? JsonObject)?.let { release ->
                    HoldReleaseTrace(
                        cause = release.requireString("cause", lineIndex),
                        sinceElapsedMs = release.requireLong("sinceElapsedMs", lineIndex),
                        freshResults = release.requireLong("freshResults", lineIndex).toInt(),
                        firstFreshReceivedElapsedMs = release.requireLong("firstFreshReceivedElapsedMs", lineIndex),
                        lastFreshReceivedElapsedMs = release.requireLong("lastFreshReceivedElapsedMs", lineIndex),
                        freshSpanMs = release.requireLong("freshSpanMs", lineIndex),
                        receiptFallbacks = release.requireLong("receiptFallbacks", lineIndex).toInt(),
                        sourceSequenceNumber = release.requireLong("sourceSequenceNumber", lineIndex),
                    )
                },
            )
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid safety_state at line ${lineIndex + 1}: ${error.message}", error)
        }
    }

    private fun parseSummary(entry: JsonObject, lineIndex: Int) = SessionTraceSummary(
        sessionId = entry.requireString("sessionId", lineIndex),
        startedAt = entry.requireLong("startedAt", lineIndex),
        completedAt = entry.requireLong("completedAt", lineIndex),
        totalFrames = entry.requireInt("totalFrames", lineIndex),
        droppedFrames = entry.requireInt("droppedFrames", lineIndex),
        totalEvents = entry.requireInt("totalEvents", lineIndex),
        blockedFrames = entry.requireInt("blockedFrames", lineIndex),
        dangerousFrames = entry.requireInt("dangerousFrames", lineIndex),
        avgProcessFrameMs = entry.requireDouble("avgProcessFrameMs", lineIndex),
        avgTotalPipelineMs = entry.requireDouble("avgTotalPipelineMs", lineIndex),
        avgInferenceMs = entry.requireDouble("avgInferenceMs", lineIndex),
        p95TotalPipelineMs = entry.requireLong("p95TotalPipelineMs", lineIndex),
        maxTotalPipelineMs = entry.requireLong("maxTotalPipelineMs", lineIndex),
        closedElapsedMs = entry.optionalLong("closedElapsedMs"),
        guidanceAlertCount = entry.optionalLong("guidanceAlertCount"),
        safetyStateCount = entry.optionalLong("safetyStateCount"),
        depthRunCount = entry.optionalLong("depthRunCount"),
    )

    private fun parseError(entry: JsonObject, lineIndex: Int) = TraceReplayError(
        sessionId = entry.requireString("sessionId", lineIndex),
        stage = entry.requireString("stage", lineIndex),
        exception = entry.requireString("exception", lineIndex),
        message = entry.optionalString("message") ?: "",
    )

    private fun JsonObject.requireString(key: String, lineIndex: Int): String {
        return element(key, lineIndex).jsonPrimitive.content
    }

    private fun JsonObject.optionalString(key: String): String? {
        return get(key)?.jsonPrimitive?.content
    }

    private fun JsonObject.requireInt(key: String, lineIndex: Int): Int {
        val value = requireLong(key, lineIndex)
        return value.toInt()
    }

    private fun JsonObject.requireLong(key: String, lineIndex: Int): Long {
        return element(key, lineIndex).jsonPrimitive.longOrNull
            ?: throw invalidField(key, lineIndex)
    }

    private fun JsonObject.requireDouble(key: String, lineIndex: Int): Double {
        return element(key, lineIndex).jsonPrimitive.doubleOrNull
            ?: throw invalidField(key, lineIndex)
    }

    private fun JsonObject.optionalDouble(key: String): Double? {
        return get(key)?.jsonPrimitive?.doubleOrNull
    }

    private fun JsonObject.optionalLong(key: String): Long? {
        return get(key)?.jsonPrimitive?.longOrNull
    }

    private fun JsonObject.optionalBoolean(key: String): Boolean? {
        return get(key)?.jsonPrimitive?.booleanOrNull
    }

    private fun JsonObject.requireBoolean(key: String, lineIndex: Int): Boolean {
        return element(key, lineIndex).jsonPrimitive.booleanOrNull
            ?: throw invalidField(key, lineIndex)
    }

    private fun JsonObject.requireStringArray(key: String, lineIndex: Int): List<String> {
        val value = element(key, lineIndex)
        val array = value as? JsonArray ?: value.jsonArray
        return array.map { item -> item.jsonPrimitive.content }
    }

    private fun JsonObject.optionalStringArray(key: String): List<String>? {
        val value = get(key) ?: return null
        val array = value as? JsonArray ?: value.jsonArray
        return array.map { item -> item.jsonPrimitive.content }
    }

    private fun JsonObject.element(key: String, lineIndex: Int): JsonElement {
        return get(key) ?: throw missingField(key, lineIndex)
    }

    private fun missingField(key: String, lineIndex: Int) =
        IllegalArgumentException("Missing field '$key' at line ${lineIndex + 1}")

    private fun invalidField(key: String, lineIndex: Int) =
        IllegalArgumentException("Invalid field '$key' at line ${lineIndex + 1}")

    private const val SESSION_START_TYPE = "session_start"
    private const val FRAME_TYPE = "frame"
    private const val OVERLAY_RENDER_TYPE = "overlay_render"
    private const val PROMPT_OUTCOME_TYPE = "prompt_outcome"
    private const val GUIDANCE_ALERT_TYPE = "guidance_alert"
    private const val SAFETY_STATE_TYPE = "safety_state"
    private const val DEPTH_RUN_TYPE = "depth_run"
    private const val SESSION_SUMMARY_TYPE = "session_summary"
    private const val ERROR_TYPE = "error"
}
