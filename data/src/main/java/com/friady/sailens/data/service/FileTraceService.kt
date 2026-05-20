package com.friady.sailens.data.service

import android.content.Context
import android.util.Log
import com.friady.sailens.domain.model.trace.FrameTrace
import com.friady.sailens.domain.model.trace.SessionTraceMetadata
import com.friady.sailens.domain.model.trace.SessionTraceSummary
import com.friady.sailens.domain.service.TraceService
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class FileTraceService(
    private val context: Context,
) : TraceService {
    private val executor = Executors.newSingleThreadExecutor()
    private val queue = ConcurrentLinkedQueue<JSONObject>()
    private var isRunning = true
    private var activeSessionId: String? = null
    private var traceFile: File? = null

    companion object {
        private const val TAG = "FileTraceService"
        private const val FLUSH_INTERVAL_MS = 2000L
        private const val MAX_TRACE_QUEUE = 4000
    }

    init {
        startPeriodicFlush()
    }

    override fun startSession(metadata: SessionTraceMetadata) {
        synchronized(this) {
            flushToDisk()
            activeSessionId = metadata.sessionId
            traceFile = createTraceFile(metadata.sessionId)
            queue.offer(TraceJsonEncoder.encodeSessionStart(metadata))
        }
    }

    override fun recordFrame(frameTrace: FrameTrace) {
        if (frameTrace.sessionId != activeSessionId) return
        queue.offer(TraceJsonEncoder.encodeFrame(frameTrace))
        trimQueueIfNeeded()
    }

    override fun recordError(sessionId: String, stage: String, throwable: Throwable) {
        if (sessionId != activeSessionId) return
        queue.offer(TraceJsonEncoder.encodeError(sessionId, stage, throwable))
        trimQueueIfNeeded()
    }

    override fun finishSession(summary: SessionTraceSummary) {
        synchronized(this) {
            if (summary.sessionId != activeSessionId) return
            queue.offer(TraceJsonEncoder.encodeSessionSummary(summary))
            flushToDisk()
            activeSessionId = null
            traceFile = null
        }
    }

    fun shutdown() {
        synchronized(this) {
            isRunning = false
            flushToDisk()
            executor.shutdown()
        }
    }

    private fun startPeriodicFlush() {
        executor.execute {
            while (isRunning) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS)
                    flushToDisk()
                } catch (_: InterruptedException) {
                    break
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to flush traces", error)
                }
            }
        }
    }

    private fun flushToDisk() {
        val file = traceFile ?: return
        if (queue.isEmpty()) return

        try {
            FileWriter(file, true).use { writer ->
                while (queue.isNotEmpty()) {
                    val entry = queue.poll() ?: break
                    writer.appendLine(entry.toString())
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to write trace file", error)
        }
    }

    private fun createTraceFile(sessionId: String): File {
        val dir = File(context.filesDir, "traces")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "trace_$sessionId.jsonl")
    }

    private fun trimQueueIfNeeded() {
        while (queue.size > MAX_TRACE_QUEUE) {
            queue.poll()
        }
    }
}

