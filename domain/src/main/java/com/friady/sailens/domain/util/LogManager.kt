package com.friady.sailens.domain.util

import android.content.Context
import android.util.Log
import com.friady.sailens.domain.model.analysis.GroundTypeChange
import com.friady.sailens.domain.model.analysis.RoadSafetyState
import com.friady.sailens.domain.model.analysis.WalkPathConnectivity
import com.friady.sailens.domain.model.scene.SceneEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors


/**
 * 日志管理器
 */
object LogManager {
    private const val TAG = "BlindAssist"
    private const val MAX_LOG_ENTRIES = 1000
    private const val FLUSH_INTERVAL_MS = 5000L

    private val logEntries = ConcurrentLinkedQueue<LogEntry>()
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss. SSS", Locale.getDefault())
    private val executor = Executors.newSingleThreadExecutor()
    private var isInitialized = false

    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
        val data: Map<String, Any>? = null,
    )

    fun initialize(context: Context) {
        val logDir = File(context.filesDir, "logs")
        if (!logDir.exists()) logDir.mkdirs()

        cleanOldLogs(logDir, 7)

        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        logFile = File(logDir, "session_$dateStr. jsonl")

        isInitialized = true
        startPeriodicFlush()
    }

    private fun cleanOldLogs(logDir: File, keepDays: Int) {
        val cutoffTime = System.currentTimeMillis() - keepDays * 24 * 60 * 60 * 1000L
        logDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                file.delete()
            }
        }
    }

    private fun startPeriodicFlush() {
        executor.execute {
            while (isInitialized) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS)
                    flushToFile()
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    fun logPerception(
        obstacleCount: Int,
        bottomCoverage: Float,
        inferenceTimeMs: Long,
        timestamp: Long,
    ) {
        log(
            "DEBUG", "Perception", "Perception completed", mapOf(
                "obstacleCount" to obstacleCount,
                "bottomCoverage" to bottomCoverage,
                "inferenceTimeMs" to inferenceTimeMs,
                "timestamp" to timestamp
            )
        )
    }

    fun logConnectivity(connectivity: WalkPathConnectivity, timestamp: Long) {
        log(
            "DEBUG", "Connectivity", "Connectivity analyzed", mapOf(
                "isBlocked" to connectivity.isBlocked,
                "isNarrowing" to connectivity.isNarrowing,
                "blockageConfidence" to connectivity.blockageConfidence,
                "narrowingConfidence" to connectivity.narrowingConfidence,
                "suggestedBias" to (connectivity.suggestedBias?.name ?: "none"),
                "timestamp" to timestamp
            )
        )
    }

    fun logRoadSafety(roadSafety: RoadSafetyState, timestamp: Long) {
        log(
            "DEBUG", "RoadSafety", "Road safety analyzed", mapOf(
                "isOnRoad" to roadSafety.isOnRoad,
                "isDangerous" to roadSafety.isDangerous,
                "roadRatio" to roadSafety.roadRatio,
                "hasVehicleOnRoad" to roadSafety.hasVehicleOnRoad,
                "dangerConfidence" to roadSafety.dangerConfidence,
                "timestamp" to timestamp
            )
        )
    }

    fun logGroundTypeChange(change: GroundTypeChange, timestamp: Long) {
        log(
            "INFO", "GroundType", "Ground type changed: ${change.from} -> ${change.to}", mapOf(
                "from" to change.from.name,
                "to" to change.to.name,
                "timestamp" to timestamp
            )
        )
    }

    fun logDecision(events: List<SceneEvent>, timestamp: Long) {
        if (events.isEmpty()) return
        log(
            "INFO", "Decision", "Events generated: ${events.size}", mapOf(
                "events" to events.map {
                    mapOf(
                        "category" to it.category.name,
                        "priority" to it.priority.name,
                        "messageKey" to it.messageKey,
                        "confidence" to it.confidence
                    )
                },
                "timestamp" to timestamp
            )
        )
    }

    fun logSpeech(category: String, priority: String, text: String, timestamp: Long) {
        log(
            "INFO", "Speech", "Speaking: $text", mapOf(
                "category" to category,
                "priority" to priority,
                "text" to text,
                "timestamp" to timestamp
            )
        )
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        log("ERROR", tag, message, throwable?.let {
            mapOf(
                "exception" to it.javaClass.simpleName,
                "exceptionMessage" to (it.message ?: ""),
                "stackTrace" to it.stackTraceToString().take(500)
            )
        })
        Log.e(TAG, "[$tag] $message", throwable)
    }

    fun logDebug(tag: String, message: String, data: Map<String, Any>? = null) {
        log("DEBUG", tag, message, data)
        Log.d(TAG, "[$tag] $message")
    }

    fun logInfo(tag: String, message: String, data: Map<String, Any>? = null) {
        log("INFO", tag, message, data)
        Log.i(TAG, "[$tag] $message")
    }

    fun logWarning(tag: String, message: String, data: Map<String, Any>? = null) {
        log("WARN", tag, message, data)
        Log.w(TAG, "[$tag] $message")
    }

    private fun log(level: String, tag: String, message: String, data: Map<String, Any>? = null) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message, data)
        logEntries.offer(entry)

        while (logEntries.size > MAX_LOG_ENTRIES) {
            logEntries.poll()
        }
    }

    private fun flushToFile() {
        val file = logFile ?: return
        if (logEntries.isEmpty()) return

        try {
            FileWriter(file, true).use { writer ->
                while (logEntries.isNotEmpty()) {
                    val entry = logEntries.poll() ?: break
                    val json = entryToJson(entry)
                    writer.appendLine(json.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush logs", e)
        }
    }

    private fun entryToJson(entry: LogEntry): JSONObject {
        return JSONObject().apply {
            put("ts", dateFormat.format(Date(entry.timestamp)))
            put("level", entry.level)
            put("tag", entry.tag)
            put("msg", entry.message)
            entry.data?.let { data ->
                put("data", JSONObject(data.mapValues { (_, v) ->
                    when (v) {
                        is List<*> -> JSONArray(v.map { item ->
                            if (item is Map<*, *>) JSONObject(item as Map<String, Any>) else item
                        })

                        is Map<*, *> -> JSONObject(v as Map<String, Any>)
                        else -> v
                    }
                }))
            }
        }
    }

    fun getLogFilePath(): String? = logFile?.absolutePath

    fun getAllLogFiles(context: Context): List<File> {
        val logDir = File(context.filesDir, "logs")
        return logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun exportLogs(context: Context): File? {
        flushToFile()
        return logFile
    }

    fun shutdown() {
        isInitialized = false
        flushToFile()
        executor.shutdown()
    }
}
