package com.friady.sailens.domain.model.trace

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
    val blockedFrames: Int,
    val dangerousFrames: Int,
    val blockedFrameRate: Double,
    val dangerousFrameRate: Double,
    val avgProcessFrameMs: Double,
    val avgInferenceMs: Double,
    val avgTotalPipelineMs: Double,
    val p95TotalPipelineMs: Long,
    val maxTotalPipelineMs: Long,
    val avgNavigationPassableRatio: Double,
    val avgBlockageConfidence: Double,
    val avgVerticalReachRatio: Double,
    val avgFloodReachRatio: Double,
    val avgWidthRetentionP25: Double,
    val maxDroppedFramesSinceLast: Int,
    val errorCount: Int,
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
        val errors = mutableListOf<TraceReplayError>()

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEachIndexed

            val entry = parseObject(line, index)
            when (val type = entry.requireString("type", index)) {
                SESSION_START_TYPE -> metadata = parseMetadata(entry, index)
                FRAME_TYPE -> frames += parseFrame(entry, index)
                SESSION_SUMMARY_TYPE -> summary = parseSummary(entry, index)
                ERROR_TYPE -> errors += parseError(entry, index)
                else -> throw IllegalArgumentException("Unsupported trace entry type '$type' at line ${index + 1}")
            }
        }

        return TraceReplaySession(
            metadata = metadata,
            frames = frames,
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
        obstacleCount = entry.requireInt("obstacleCount", lineIndex),
        eventCount = entry.requireInt("eventCount", lineIndex),
        isBlocked = entry.requireBoolean("isBlocked", lineIndex),
        isNarrowing = entry.requireBoolean("isNarrowing", lineIndex),
        isRoadDangerous = entry.requireBoolean("isRoadDangerous", lineIndex),
        navigationPassableRatio = entry.optionalDouble("navigationPassableRatio") ?: 0.0,
        blockageConfidence = entry.optionalDouble("blockageConfidence") ?: 0.0,
        verticalReachRatio = entry.optionalDouble("verticalReachRatio") ?: 0.0,
        floodReachRatio = entry.optionalDouble("floodReachRatio") ?: 0.0,
        widthRetentionP25 = entry.optionalDouble("widthRetentionP25") ?: 0.0,
        messageKeys = entry.requireStringArray("messageKeys", lineIndex),
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

    private fun JsonObject.requireBoolean(key: String, lineIndex: Int): Boolean {
        return element(key, lineIndex).jsonPrimitive.booleanOrNull
            ?: throw invalidField(key, lineIndex)
    }

    private fun JsonObject.requireStringArray(key: String, lineIndex: Int): List<String> {
        val value = element(key, lineIndex)
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
    private const val SESSION_SUMMARY_TYPE = "session_summary"
    private const val ERROR_TYPE = "error"
}
