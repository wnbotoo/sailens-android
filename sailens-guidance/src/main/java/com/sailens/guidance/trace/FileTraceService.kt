package com.sailens.guidance.trace

import android.content.Context
import android.os.SystemClock
import com.sailens.guidance.config.TraceRuntimeConfig
import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
import com.sailens.core.log.LogService
import com.sailens.guidance.service.TraceService
import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.util.concurrent.Executors

/**
 * @param directory where the trace files go.
 * @param elapsedClock elapsed-realtime milliseconds, for the window a session's trace observes.
 * @param appendEntry encodes one entry onto the open file.
 * @param wallClock wall-clock milliseconds, for the time written on the safety state that opens a session.
 */
class FileTraceService internal constructor(
    private val directory: () -> File,
    private val logService: LogService,
    private val traceRuntimeConfig: TraceRuntimeConfig,
    private val elapsedClock: () -> Long,
    private val appendEntry: (Writer, TraceQueueEntry) -> Unit,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : TraceService {
    constructor(
        context: Context,
        logService: LogService,
        traceRuntimeConfig: TraceRuntimeConfig = TraceRuntimeConfig(enabled = true),
    ) : this(
        directory = { File(context.filesDir, "traces") },
        logService = logService,
        traceRuntimeConfig = traceRuntimeConfig,
        elapsedClock = SystemClock::elapsedRealtime,
        appendEntry = { writer, entry -> writer.appendLine(entry.encode().toString()) },
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val queue = CountedTraceQueue<TraceQueueEntry>()

    /** The alert and safety-state records, ordered against the session's opening and closing without this object's lock. */
    private val ledger = SafetyTraceLedger(queue)

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
            ledger.attach(metadata.copy(openedElapsedMs = elapsedClock(), safetyTrace = true), openedAt = wallClock())
            trim()
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

    override fun recordPromptOutcome(outcome: PromptOutcomeTrace) {
        val sessionId = activeSessionId ?: return
        enqueue(TraceQueueEntry.PromptOutcome(outcome.copy(sessionId = sessionId)))
    }

    override fun recordError(sessionId: String, stage: String, throwable: Throwable) {
        if (sessionId != activeSessionId) return
        enqueue(TraceQueueEntry.Error(sessionId, stage, throwable))
    }

    /** Never takes this object's lock, which a flush holds while it writes to disk. */
    override fun recordGuidanceAlert(alert: GuidanceAlertTrace) {
        if (ledger.recordAlert(alert)) trim()
    }

    /** Never takes this object's lock, which a flush holds while it writes to disk. */
    override fun recordSafetyState(state: SafetyStateTrace) {
        if (ledger.recordState(state)) trim()
    }

    override fun finishSession(summary: SessionTraceSummary) {
        synchronized(this) {
            if (summary.sessionId != activeSessionId) return
            ledger.detach(summary.copy(closedElapsedMs = elapsedClock()))
            trim()
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
                while (true) {
                    val entry = queue.pollCounted() ?: break
                    appendEntry(writer, entry)
                }
            }
        } catch (error: Exception) {
            logService.error(TAG, "Failed to write trace file", error)
        }
    }

    private fun createTraceFile(sessionId: String): File {
        val dir = directory()
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "trace_$sessionId.jsonl")
    }

    private fun enqueue(entry: TraceQueueEntry) {
        queue.offerCounted(entry)
        trim()
    }

    /** Drops the oldest entries beyond the bound. Outside the ledger's lock, always. */
    private fun trim() = queue.trimIfNeeded(traceRuntimeConfig.maxQueuedEntries)
}
