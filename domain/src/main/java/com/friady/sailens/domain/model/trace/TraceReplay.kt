package com.friady.sailens.domain.model.trace

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
    val maxDroppedFramesSinceLast: Int,
    val errorCount: Int,
    val uniqueMessageKeys: List<String>,
)

object TraceReplayParser {
    fun parse(lines: List<String>): TraceReplaySession {
        var metadata: SessionTraceMetadata? = null
        var summary: SessionTraceSummary? = null
        val frames = mutableListOf<FrameTrace>()
        val errors = mutableListOf<TraceReplayError>()

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEachIndexed

            when (val type = requireString(line, "type", index)) {
                SESSION_START_TYPE -> metadata = parseMetadata(line, index)
                FRAME_TYPE -> frames += parseFrame(line, index)
                SESSION_SUMMARY_TYPE -> summary = parseSummary(line, index)
                ERROR_TYPE -> errors += parseError(line, index)
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

    private fun parseMetadata(line: String, lineIndex: Int) = SessionTraceMetadata(
        sessionId = requireString(line, "sessionId", lineIndex),
        startedAt = requireLong(line, "startedAt", lineIndex),
        pipelineMode = requireString(line, "pipelineMode", lineIndex),
        targetHardwareProfile = requireString(line, "targetHardwareProfile", lineIndex),
    )

    private fun parseFrame(line: String, lineIndex: Int) = FrameTrace(
        sessionId = requireString(line, "sessionId", lineIndex),
        sequenceNumber = requireLong(line, "sequenceNumber", lineIndex),
        frameTimestamp = requireLong(line, "frameTimestamp", lineIndex),
        frameWidth = requireInt(line, "frameWidth", lineIndex),
        frameHeight = requireInt(line, "frameHeight", lineIndex),
        droppedFramesSinceLast = requireInt(line, "droppedFramesSinceLast", lineIndex),
        processFrameMs = requireLong(line, "processFrameMs", lineIndex),
        inferenceMs = requireLong(line, "inferenceMs", lineIndex),
        analyzeSceneMs = requireLong(line, "analyzeSceneMs", lineIndex),
        decideEventsMs = requireLong(line, "decideEventsMs", lineIndex),
        totalPipelineMs = requireLong(line, "totalPipelineMs", lineIndex),
        obstacleCount = requireInt(line, "obstacleCount", lineIndex),
        eventCount = requireInt(line, "eventCount", lineIndex),
        isBlocked = requireBoolean(line, "isBlocked", lineIndex),
        isNarrowing = requireBoolean(line, "isNarrowing", lineIndex),
        isRoadDangerous = requireBoolean(line, "isRoadDangerous", lineIndex),
        messageKeys = requireStringArray(line, "messageKeys", lineIndex),
    )

    private fun parseSummary(line: String, lineIndex: Int) = SessionTraceSummary(
        sessionId = requireString(line, "sessionId", lineIndex),
        startedAt = requireLong(line, "startedAt", lineIndex),
        completedAt = requireLong(line, "completedAt", lineIndex),
        totalFrames = requireInt(line, "totalFrames", lineIndex),
        droppedFrames = requireInt(line, "droppedFrames", lineIndex),
        totalEvents = requireInt(line, "totalEvents", lineIndex),
        blockedFrames = requireInt(line, "blockedFrames", lineIndex),
        dangerousFrames = requireInt(line, "dangerousFrames", lineIndex),
        avgProcessFrameMs = requireDouble(line, "avgProcessFrameMs", lineIndex),
        avgTotalPipelineMs = requireDouble(line, "avgTotalPipelineMs", lineIndex),
        avgInferenceMs = requireDouble(line, "avgInferenceMs", lineIndex),
        p95TotalPipelineMs = requireLong(line, "p95TotalPipelineMs", lineIndex),
        maxTotalPipelineMs = requireLong(line, "maxTotalPipelineMs", lineIndex),
    )

    private fun parseError(line: String, lineIndex: Int) = TraceReplayError(
        sessionId = requireString(line, "sessionId", lineIndex),
        stage = requireString(line, "stage", lineIndex),
        exception = requireString(line, "exception", lineIndex),
        message = optionalString(line, "message") ?: "",
    )

    private fun requireString(line: String, key: String, lineIndex: Int): String {
        val match = stringFieldRegex(key).find(line)
            ?: throw missingField(key, lineIndex)
        return decodeJsonString(match.groupValues[1])
    }

    private fun optionalString(line: String, key: String): String? =
        stringFieldRegex(key).find(line)?.groupValues?.get(1)?.let(::decodeJsonString)

    private fun requireInt(line: String, key: String, lineIndex: Int): Int =
        requireNumber(line, key, lineIndex).toInt()

    private fun requireLong(line: String, key: String, lineIndex: Int): Long =
        requireNumber(line, key, lineIndex).toLong()

    private fun requireDouble(line: String, key: String, lineIndex: Int): Double {
        val match = numberFieldRegex(key).find(line)
            ?: throw missingField(key, lineIndex)
        return match.groupValues[1].toDouble()
    }

    private fun requireBoolean(line: String, key: String, lineIndex: Int): Boolean {
        val match = booleanFieldRegex(key).find(line)
            ?: throw missingField(key, lineIndex)
        return match.groupValues[1].toBooleanStrict()
    }

    private fun requireStringArray(line: String, key: String, lineIndex: Int): List<String> {
        val match = arrayFieldRegex(key).find(line)
            ?: throw missingField(key, lineIndex)
        val body = match.groupValues[1].trim()
        if (body.isBlank()) return emptyList()

        return stringValueRegex.findAll(body)
            .map { decodeJsonString(it.groupValues[1]) }
            .toList()
    }

    private fun requireNumber(line: String, key: String, lineIndex: Int): String {
        val match = numberFieldRegex(key).find(line)
            ?: throw missingField(key, lineIndex)
        return match.groupValues[1]
    }

    private fun missingField(key: String, lineIndex: Int) =
        IllegalArgumentException("Missing field '$key' at line ${lineIndex + 1}")

    private fun stringFieldRegex(key: String) = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    private fun numberFieldRegex(key: String) = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
    private fun booleanFieldRegex(key: String) = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
    private fun arrayFieldRegex(key: String) = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\\[(.*?)]")

    private val stringValueRegex = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")

    private fun decodeJsonString(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current == '\\' && index + 1 < value.length) {
                val escaped = value[index + 1]
                append(
                    when (escaped) {
                        '\\' -> '\\'
                        '"' -> '"'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'b' -> '\b'
                        'f' -> '\u000C'
                        else -> escaped
                    }
                )
                index += 2
            } else {
                append(current)
                index++
            }
        }
    }

    private const val SESSION_START_TYPE = "session_start"
    private const val FRAME_TYPE = "frame"
    private const val SESSION_SUMMARY_TYPE = "session_summary"
    private const val ERROR_TYPE = "error"
}

