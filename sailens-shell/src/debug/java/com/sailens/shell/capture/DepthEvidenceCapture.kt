package com.sailens.shell.capture

import com.sailens.core.frame.ImageFrame
import com.sailens.core.log.LogService
import com.sailens.guidance.depth.geometry.DepthEvidenceRecorder
import com.sailens.guidance.depth.geometry.DepthEvidenceRun
import com.sailens.guidance.depth.geometry.DepthEvidenceSlot
import com.sailens.guidance.geometry.SupportGuardRule
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.DepthBlobRef
import com.sailens.guidance.trace.capture.DepthEvidenceData
import com.sailens.guidance.trace.capture.DepthEvidenceFiles
import com.sailens.guidance.trace.capture.DepthEvidenceManifest
import com.sailens.guidance.trace.capture.DepthEvidenceRecord
import com.sailens.guidance.trace.capture.DepthEvidenceStats
import com.sailens.guidance.trace.capture.DepthFrameRecord
import com.sailens.guidance.trace.capture.DepthOutputRecord
import com.sailens.guidance.trace.capture.DepthPlaneRecord
import com.sailens.guidance.trace.capture.toRecord
import com.sailens.guidance.trace.regression.toRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicLong

/**
 * Sizes of the depth evidence stream. Each record holds one frame's planes and one output (about 2.2 MB at
 * 1280×720 with a 392×224 output), so both are small on purpose: a run that finds neither a free buffer
 * nor room in the queue is dropped and counted, never waited for.
 */
internal data class DepthEvidenceCaptureConfig(
    val poolSize: Int = 4,
    val queueCapacity: Int = 4,
)

/**
 * The depth evidence stream of a capture (docs/field-capture.md §11): the depth lane's [DepthEvidenceRecorder],
 * writing `depth.jsonl` and `depth.bin` into the capture's directory.
 *
 * Lifecycle, as for the model-regression record: [startSession] runs synchronously when the trace session
 * starts, before the lane can run, so the session's first fit is not missed; records wait in the bounded
 * queue until [attach] gives the directory; [finish] stops, writes what is queued and returns the counters;
 * [abort] stops without writing.
 *
 * Observation only. [copy] runs on the lane's thread while it holds the frame: one array copy per plane into
 * a pooled buffer, no conversion. Nothing here throws into the lane or blocks it; a run that cannot be kept is
 * counted in [DepthEvidenceStats.dropped], and the gap it leaves in the fit ordinals is what a replay stops at.
 */
internal class DepthEvidenceCapture(
    private val log: LogService,
    private val writerDispatcher: CoroutineDispatcher,
    private val config: DepthEvidenceCaptureConfig = DepthEvidenceCaptureConfig(),
    /** Runs after a copy is registered and before it is made; tests hold a copy there. */
    private val afterRegister: () -> Unit = {},
) : DepthEvidenceRecorder {

    @Volatile
    private var session: Session? = null

    /** Bytes the active session's stream wrote so far, for the storage cap. */
    val bytesWritten: Long get() = session?.bytes?.get() ?: 0

    /**
     * A session replaced before its capture ended: a new Guidance session can start before the writer thread has
     * ended the previous capture. It takes no new copies but writes what it holds until its capture ends it, so
     * that capture's manifest counts it whole.
     */
    @Volatile
    private var superseded: Session? = null

    fun startSession(sessionId: String) {
        superseded?.let { abandon(it, "superseded twice") }
        superseded = session
        session = Session(sessionId)
    }

    private fun sessionFor(sessionId: String): Session? =
        session?.takeIf { it.id == sessionId } ?: superseded?.takeIf { it.id == sessionId }

    /**
     * The manifest block written when the capture starts; [finish] fills in the counters. The guard rule is the one the
     * lane's fitter takes by default, so a replay fits with it.
     */
    fun manifestBlock(): DepthEvidenceManifest = DepthEvidenceManifest(
        poolSize = config.poolSize,
        queueCapacity = config.queueCapacity,
        groundFitRule = SupportGuardRule.CURRENT.id,
    )

    /** The session directory exists now: the writer may start. */
    fun attach(sessionId: String, directory: File) {
        val s = session?.takeIf { it.id == sessionId && it.recording } ?: return
        s.writerJob = s.scope.launch { s.write(directory) }
    }

    /** Stops recording, writes what is queued and returns the counters. */
    suspend fun finish(sessionId: String): DepthEvidenceStats? {
        val s = sessionFor(sessionId) ?: return null
        // Settles every copy still lent out before the counts are read: a run that submits later writes nothing and
        // is already counted.
        s.close()
        val writer = s.writerJob
        if (writer == null) s.discardQueued() else writer.join()
        // Whatever the writer left behind (it failed) is dropped, and counted.
        s.discardQueued()
        if (session === s) session = null
        if (superseded === s) superseded = null
        return s.stats()
    }

    /** Stops recording without writing what is queued (the capture failed). */
    fun abort(sessionId: String, reason: String): DepthEvidenceStats? {
        val s = sessionFor(sessionId) ?: return null
        abandon(s, reason)
        if (session === s) session = null
        if (superseded === s) superseded = null
        return s.stats()
    }

    private fun abandon(s: Session, reason: String) {
        s.close()
        s.writerJob?.cancel()
        s.discardQueued()
        log.warning(TAG, "Depth evidence stopped: $reason")
    }

    override fun copy(traceSessionId: String, generation: Long, runId: Long, frame: ImageFrame): DepthEvidenceSlot? {
        val s = session?.takeIf { it.recording && it.id == traceSessionId } ?: return null
        return s.copy(generation, runId, frame)
    }

    private enum class Drop { NO_BUFFER, QUEUE_FULL, WRITE_FAILED, AT_END }

    /** One frame's planes, copied as the native preprocessing was given them; reused between runs. */
    private class Planes {
        var y = ByteArray(0)
        var u = ByteArray(0)
        var v = ByteArray(0)
        var yLength = 0
        var uLength = 0
        var vLength = 0

        fun fill(frame: ImageFrame): Boolean {
            val yuv = frame.yuvData ?: return false
            yLength = yuv.y.bytes.size
            uLength = yuv.u.bytes.size
            vLength = yuv.v.bytes.size
            if (y.size < yLength) y = ByteArray(yLength)
            if (u.size < uLength) u = ByteArray(uLength)
            if (v.size < vLength) v = ByteArray(vLength)
            System.arraycopy(yuv.y.bytes, 0, y, 0, yLength)
            System.arraycopy(yuv.u.bytes, 0, u, 0, uLength)
            System.arraycopy(yuv.v.bytes, 0, v, 0, vLength)
            return true
        }
    }

    /** What the frame says about itself, taken with the copy. */
    private class FrameFacts(
        val sequence: Long,
        val timestamp: Long,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val yRowStride: Int,
        val yPixelStride: Int,
        val uRowStride: Int,
        val uPixelStride: Int,
        val vRowStride: Int,
        val vPixelStride: Int,
    )

    private class Pending(
        val generation: Long,
        val runId: Long,
        val frame: FrameFacts,
        val planes: Planes,
        val run: DepthEvidenceRun,
    )

    private inner class Session(val id: String) {
        @Volatile var recording = true
        val scope = CoroutineScope(SupervisorJob() + writerDispatcher)
        var writerJob: Job? = null
        val queue = Channel<Pending>(config.queueCapacity)

        private val free = ArrayDeque<Planes>()
        private var allocated = 0

        /**
         * The runs registered for a copy -- from before the copy is made -- and not yet settled. [close], a submit, a
         * discard and the copy's own outcome each take [lending] for their step, so a run is settled exactly once: by
         * the lane, by a failed copy, or by [close] as dropped at the end.
         */
        private val lending = Any()

        /** In the order lent, so the first one counted at the end is the earliest run. */
        private val lent = LinkedHashSet<Slot>()

        /**
         * Stops taking runs: no new copy, the queue closed, and every copy still lent out counted as dropped at the end.
         * After it the counters change only by what the writer does with what was already queued.
         */
        fun close() {
            synchronized(lending) {
                recording = false
                queue.close()
                lent.forEach { drop(it.generation, it.runId, Drop.AT_END) }
                lent.clear()
            }
        }

        val recordsWritten = AtomicLong()
        val droppedNoBuffer = AtomicLong()
        val droppedQueueFull = AtomicLong()
        val droppedWriteFailed = AtomicLong()
        val droppedAtEnd = AtomicLong()
        @Volatile var firstDroppedRunId: Long? = null
        @Volatile var firstDroppedGeneration: Long? = null
        val bytes = AtomicLong()
        private var hookFailures = 0L

        fun stats(): DepthEvidenceStats {
            val noBuffer = droppedNoBuffer.get()
            val queueFull = droppedQueueFull.get()
            val writeFailed = droppedWriteFailed.get()
            val atEnd = droppedAtEnd.get()
            return DepthEvidenceStats(
                recordsWritten = recordsWritten.get(),
                dropped = noBuffer + queueFull + writeFailed + atEnd,
                droppedNoBuffer = noBuffer,
                droppedQueueFull = queueFull,
                droppedWriteFailed = writeFailed,
                droppedAtEnd = atEnd,
                firstDroppedRunId = firstDroppedRunId,
                firstDroppedGeneration = firstDroppedGeneration,
                bytes = bytes.get(),
            )
        }

        fun hookFailed(e: Exception) {
            if (hookFailures++ == 0L) log.warning(TAG, "Depth evidence copy failed", throwable = e)
        }

        fun drop(generation: Long, runId: Long, why: Drop) {
            when (why) {
                Drop.NO_BUFFER -> droppedNoBuffer
                Drop.QUEUE_FULL -> droppedQueueFull
                Drop.WRITE_FAILED -> droppedWriteFailed
                Drop.AT_END -> droppedAtEnd
            }.incrementAndGet()
            synchronized(this) {
                if (firstDroppedRunId == null) {
                    firstDroppedRunId = runId
                    firstDroppedGeneration = generation
                    log.warning(TAG, "Depth evidence dropped run $runId of depth session $generation ($why); its fit replays only before it")
                }
            }
        }

        private fun borrow(): Planes? = synchronized(free) {
            free.removeFirstOrNull() ?: if (allocated < config.poolSize) Planes().also { allocated++ } else null
        }

        fun giveBack(planes: Planes) = synchronized(free) { free.addLast(planes) }

        /**
         * The run is registered as lent before anything is copied, so that [close] settles it whatever point the copy
         * has reached: a run that comes in while the stream is open is counted exactly once -- written, dropped by
         * whoever settles it first, or (closed before it came in) not part of this stream at all.
         */
        fun copy(generation: Long, runId: Long, frame: ImageFrame): DepthEvidenceSlot? {
            val slot = Slot(generation, runId)
            synchronized(lending) {
                if (!recording) return null
                lent += slot
            }
            afterRegister()
            val planes = borrow()
            val yuv = frame.yuvData
            val filled = try {
                planes != null && yuv != null && planes.fill(frame)
            } catch (e: Exception) {
                hookFailed(e)
                false
            }
            if (planes == null || yuv == null || !filled) {
                planes?.let(::giveBack)
                // Unless close() settled it meanwhile, and counted it then.
                synchronized(lending) { if (lent.remove(slot)) drop(generation, runId, Drop.NO_BUFFER) }
                return null
            }
            val facts = FrameFacts(
                sequence = frame.sequenceNumber,
                timestamp = frame.timestamp,
                width = frame.width,
                height = frame.height,
                rotationDegrees = frame.rotationDegrees,
                yRowStride = yuv.y.rowStride,
                yPixelStride = yuv.y.pixelStride,
                uRowStride = yuv.u.rowStride,
                uPixelStride = yuv.u.pixelStride,
                vRowStride = yuv.v.rowStride,
                vPixelStride = yuv.v.pixelStride,
            )
            synchronized(lending) {
                slot.planes = planes
                slot.facts = facts
            }
            return slot
        }

        /** A run's copy; its planes are filled in after it is registered, under [lending]. */
        private inner class Slot(val generation: Long, val runId: Long) : DepthEvidenceSlot {
            var planes: Planes? = null
            var facts: FrameFacts? = null

            override fun submit(run: DepthEvidenceRun) {
                synchronized(lending) {
                    // Already settled: by an earlier call, or by close() as dropped at the end.
                    if (!lent.remove(this)) return
                    val planes = planes
                    val facts = facts
                    if (planes == null || facts == null) {
                        // The lane holds a slot only once copy() returned it filled; counted, never lost, all the same.
                        drop(generation, runId, Drop.NO_BUFFER)
                        return
                    }
                    val sent = queue.trySend(Pending(generation, runId, facts, planes, run))
                    if (sent.isFailure) {
                        giveBack(planes)
                        drop(generation, runId, if (sent.isClosed) Drop.AT_END else Drop.QUEUE_FULL)
                    }
                }
            }

            override fun discard() {
                synchronized(lending) {
                    if (lent.remove(this)) planes?.let(::giveBack)
                }
            }
        }

        fun discardQueued() {
            while (true) {
                val item = queue.tryReceive().getOrNull() ?: break
                giveBack(item.planes)
                drop(item.generation, item.runId, Drop.AT_END)
            }
        }

        /** Writer thread: drains [queue] into the session directory until it is closed. */
        suspend fun write(directory: File) {
            val dataFile = File(directory, DepthEvidenceFiles.DATA_FILE)
            var offset = dataFile.length()
            val data: FileOutputStream
            val lines: BufferedWriter
            try {
                data = FileOutputStream(dataFile, true)
                lines = try {
                    BufferedWriter(OutputStreamWriter(FileOutputStream(File(directory, DepthEvidenceFiles.RECORDS_FILE), true), Charsets.UTF_8))
                } catch (e: Exception) {
                    runCatching { data.close() }
                    throw e
                }
            } catch (e: Exception) {
                // What stays queued is dropped and counted when the stream finishes.
                log.warning(TAG, "Depth evidence writer could not start", throwable = e)
                return
            }
            var outputBytes = ByteArray(0)
            try {
                for (item in queue) {
                    var planesBack = false
                    try {
                        val p = item.planes
                        val y = DepthEvidenceData.ref(offset, p.y, p.yLength)
                        data.write(p.y, 0, p.yLength)
                        offset += p.yLength
                        val u = DepthEvidenceData.ref(offset, p.u, p.uLength)
                        data.write(p.u, 0, p.uLength)
                        offset += p.uLength
                        val v = DepthEvidenceData.ref(offset, p.v, p.vLength)
                        data.write(p.v, 0, p.vLength)
                        offset += p.vLength
                        giveBack(p)
                        planesBack = true

                        val map = item.run.run.map
                        outputBytes = DepthEvidenceData.floatBytes(map.values, outputBytes)
                        val outputLength = map.values.size * Float.SIZE_BYTES
                        val output = DepthEvidenceData.ref(offset, outputBytes, outputLength)
                        data.write(outputBytes, 0, outputLength)
                        offset += outputLength
                        data.flush()

                        val line = CaptureSchema.encodeRecord(item.toRecord(y, u, v, output, map.width, map.height))
                        lines.write(line)
                        lines.write("\n")
                        if (queue.isEmpty) lines.flush()
                        bytes.addAndGet(p.yLength.toLong() + p.uLength + p.vLength + outputLength + line.length + 1)
                        recordsWritten.incrementAndGet()
                    } catch (e: Exception) {
                        // depth.bin may now hold part of this run's bytes; the next record's refs start after
                        // whatever reached the file, and a torn blob is found by its CRC.
                        offset = dataFile.length()
                        if (!planesBack) giveBack(item.planes)
                        drop(item.generation, item.runId, Drop.WRITE_FAILED)
                    }
                }
                lines.flush()
            } finally {
                runCatching { lines.close() }
                runCatching { data.close() }
            }
        }

        private fun Pending.toRecord(
            y: DepthBlobRef,
            u: DepthBlobRef,
            v: DepthBlobRef,
            output: DepthBlobRef,
            outputWidth: Int,
            outputHeight: Int,
        ): DepthEvidenceRecord {
            val chromaWidth = (frame.width + 1) / 2
            val chromaHeight = (frame.height + 1) / 2
            return DepthEvidenceRecord(
                generation = generation,
                runId = runId,
                fitOrdinal = run.fitOrdinal,
                outcome = run.outcome,
                frameSequence = frame.sequence,
                sensorTimestampNanos = frame.timestamp,
                frame = DepthFrameRecord(
                    width = frame.width,
                    height = frame.height,
                    rotationDegrees = frame.rotationDegrees,
                    y = DepthPlaneRecord(frame.width, frame.height, frame.yRowStride, frame.yPixelStride, y),
                    u = DepthPlaneRecord(chromaWidth, chromaHeight, frame.uRowStride, frame.uPixelStride, u),
                    v = DepthPlaneRecord(chromaWidth, chromaHeight, frame.vRowStride, frame.vPixelStride, v),
                ),
                geometry = run.geometry.toRecord(),
                transform = run.run.map.transform.toRecord(),
                gridWidth = run.gridWidth,
                gridHeight = run.gridHeight,
                output = DepthOutputRecord(outputWidth, outputHeight, output),
                observation = run.observation?.toRecord(),
                modelShape = run.run.shapeLabel,
                accelerator = run.run.runtimeInfo.accelerator,
                detail = run.run.detail,
            )
        }
    }

    private companion object {
        const val TAG = "DepthEvidence"
    }
}
