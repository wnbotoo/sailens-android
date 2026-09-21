package com.sailens.guidance.trace

import android.content.Context
import com.sailens.guidance.config.TraceRuntimeConfig
import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
import com.sailens.core.log.LogService
import com.sailens.guidance.service.TraceService
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FileTraceService(
    private val context: Context,
    private val logService: LogService,
    private val traceRuntimeConfig: TraceRuntimeConfig = TraceRuntimeConfig(enabled = true),
) : TraceService {
    private val executor = Executors.newSingleThreadExecutor()
    private val queue = ConcurrentLinkedQueue<TraceQueueEntry>()
    private val queuedEntries = AtomicInteger(0)

    @Volatile
    private var isRunning = true

    @Volatile
    private var activeSessionId: String? = null

    @Volatile
    private var traceFile: File? = null

    companion object {
        private const val TAG = "FileTraceService"
        private const val FLUSH_INTERVAL_MS = 2000L
    }

    init {
        startPeriodicFlush()
    }

    override fun startSession(metadata: SessionTraceMetadata) {
        synchronized(this) {
            flushToDisk()
            activeSessionId = metadata.sessionId
            traceFile = createTraceFile(metadata.sessionId)
            enqueue(TraceQueueEntry.SessionStart(metadata))
        }
    }

    override fun recordFrame(frameTrace: FrameTrace) {
        if (frameTrace.sessionId != activeSessionId) return
        enqueue(TraceQueueEntry.Frame(frameTrace))
    }

    override fun recordOverlayRender(
        renderedAt: Long,
        renderMs: Long,
        overlayMode: String,
        bitmapRendered: Boolean,
        sourceSequenceNumber: Long,
        sourcePipelineCompletedAt: Long,
        sourceAgeMs: Long,
    ) {
        val sessionId = activeSessionId ?: return
        enqueue(
            TraceQueueEntry.OverlayRender(
                sessionId = sessionId,
                renderedAt = renderedAt,
                renderMs = renderMs,
                overlayMode = overlayMode,
                bitmapRendered = bitmapRendered,
                sourceSequenceNumber = sourceSequenceNumber,
                sourcePipelineCompletedAt = sourcePipelineCompletedAt,
                sourceAgeMs = sourceAgeMs,
            )
        )
    }

    override fun recordError(sessionId: String, stage: String, throwable: Throwable) {
        if (sessionId != activeSessionId) return
        enqueue(TraceQueueEntry.Error(sessionId, stage, throwable))
    }

    override fun finishSession(summary: SessionTraceSummary) {
        synchronized(this) {
            if (summary.sessionId != activeSessionId) return
            enqueue(TraceQueueEntry.SessionSummary(summary))
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
                    logService.error(TAG, "Failed to flush traces", error)
                }
            }
        }
    }

    @Synchronized
    private fun flushToDisk() {
        val file = traceFile ?: return
        if (queue.isEmpty()) return

        try {
            FileWriter(file, true).use { writer ->
                while (queue.isNotEmpty()) {
                    val entry = queue.poll() ?: break
                    queuedEntries.decrementAndGet()
                    writer.appendLine(entry.encode().toString())
                }
            }
        } catch (error: Exception) {
            logService.error(TAG, "Failed to write trace file", error)
        }
    }

    private fun createTraceFile(sessionId: String): File {
        val dir = File(context.filesDir, "traces")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "trace_$sessionId.jsonl")
    }

    private fun enqueue(entry: TraceQueueEntry) {
        queue.offer(entry)
        queuedEntries.incrementAndGet()
        trimQueueIfNeeded()
    }

    private fun trimQueueIfNeeded() {
        while (queuedEntries.get() > traceRuntimeConfig.maxQueuedEntries) {
            if (queue.poll() == null) {
                queuedEntries.set(0)
                return
            }
            queuedEntries.decrementAndGet()
        }
    }

    private sealed interface TraceQueueEntry {
        fun encode(): JSONObject

        data class SessionStart(
            val metadata: SessionTraceMetadata,
        ) : TraceQueueEntry {
            override fun encode(): JSONObject = TraceJsonEncoder.encodeSessionStart(metadata)
        }

        data class Frame(
            val frameTrace: FrameTrace,
        ) : TraceQueueEntry {
            override fun encode(): JSONObject = TraceJsonEncoder.encodeFrame(frameTrace)
        }

        data class OverlayRender(
            val sessionId: String,
            val renderedAt: Long,
            val renderMs: Long,
            val overlayMode: String,
            val bitmapRendered: Boolean,
            val sourceSequenceNumber: Long,
            val sourcePipelineCompletedAt: Long,
            val sourceAgeMs: Long,
        ) : TraceQueueEntry {
            override fun encode(): JSONObject = TraceJsonEncoder.encodeOverlayRender(
                sessionId = sessionId,
                renderedAt = renderedAt,
                renderMs = renderMs,
                overlayMode = overlayMode,
                bitmapRendered = bitmapRendered,
                sourceSequenceNumber = sourceSequenceNumber,
                sourcePipelineCompletedAt = sourcePipelineCompletedAt,
                sourceAgeMs = sourceAgeMs,
            )
        }

        data class Error(
            val sessionId: String,
            val stage: String,
            val throwable: Throwable,
        ) : TraceQueueEntry {
            override fun encode(): JSONObject = TraceJsonEncoder.encodeError(sessionId, stage, throwable)
        }

        data class SessionSummary(
            val summary: SessionTraceSummary,
        ) : TraceQueueEntry {
            override fun encode(): JSONObject = TraceJsonEncoder.encodeSessionSummary(summary)
        }
    }
}
