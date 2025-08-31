package com.friady.sailens.data.service

import android.content.Context
import android.util.Log
import com.friady.sailens.domain.service.LogService
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * 文件日志服务实现
 */
class FileLogService(
    private val context: Context,
) : LogService {

    private val logEntries = ConcurrentLinkedQueue<LogEntry>()
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss. SSS", Locale.getDefault())
    private val executor = Executors.newSingleThreadExecutor()
    private var isInitialized = false

    companion object {
        private const val TAG = "BlindAssist"
        private const val MAX_LOG_ENTRIES = 1000
        private const val FLUSH_INTERVAL_MS = 5000L
    }

    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
        val data: Map<String, Any>? = null,
    )

    init {
        initialize()
    }

    private fun initialize() {
        val logDir = File(context.filesDir, "logs")
        if (!logDir.exists()) logDir.mkdirs()

        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        logFile = File(logDir, "session_$dateStr. jsonl")

        isInitialized = true
        startPeriodicFlush()
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

    override fun debug(tag: String, message: String, data: Map<String, Any>?) {
        log("DEBUG", tag, message, data)
        Log.d(TAG, "[$tag] $message")
    }

    override fun info(tag: String, message: String, data: Map<String, Any>?) {
        log("INFO", tag, message, data)
        Log.i(TAG, "[$tag] $message")
    }

    override fun warning(tag: String, message: String, data: Map<String, Any>?) {
        log("WARN", tag, message, data)
        Log.w(TAG, "[$tag] $message")
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        val data = throwable?.let {
            mapOf(
                "exception" to it.javaClass.simpleName,
                "exceptionMessage" to (it.message ?: ""),
                "stackTrace" to it.stackTraceToString().take(500)
            )
        }
        log("ERROR", tag, message, data)
        Log.e(TAG, "[$tag] $message", throwable)
    }

    private fun log(level: String, tag: String, message: String, data: Map<String, Any>?) {
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
            entry.data?.let { put("data", JSONObject(it)) }
        }
    }

    fun shutdown() {
        isInitialized = false
        flushToFile()
        executor.shutdown()
    }
}