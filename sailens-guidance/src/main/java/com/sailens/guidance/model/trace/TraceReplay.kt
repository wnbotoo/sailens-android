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
    val errors: List<TraceReplayError>,
    val summary: SessionTraceSummary?,
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
)

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
        val errors = mutableListOf<TraceReplayError>()

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEachIndexed

            val entry = parseObject(line, index)
            when (val type = entry.requireString("type", index)) {
                SESSION_START_TYPE -> metadata = parseMetadata(entry, index)
                FRAME_TYPE -> frames += parseFrame(entry, index)
                OVERLAY_RENDER_TYPE -> overlayRenders += parseOverlayRender(entry, index)
                SESSION_SUMMARY_TYPE -> summary = parseSummary(entry, index)
                ERROR_TYPE -> errors += parseError(entry, index)
                else -> throw IllegalArgumentException("Unsupported trace entry type '$type' at line ${index + 1}")
            }
        }

        return TraceReplaySession(
            metadata = metadata,
            frames = frames,
            overlayRenders = overlayRenders,
            errors = errors,
            summary = summary,
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
    private const val SESSION_SUMMARY_TYPE = "session_summary"
    private const val ERROR_TYPE = "error"
}
