package com.sailens.shell.capture

import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.Yuv420FrameData
import com.sailens.core.frame.YuvPlaneData
import com.sailens.core.log.LogService
import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.model.analysis.ConnectivityStats
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.PromptDeliveryChannels
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.processor.analysis.FrameQualityResult
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.ClassMapStore
import com.sailens.guidance.trace.capture.FrameEncoding
import com.sailens.guidance.trace.capture.RegressionFrameRecord
import com.sailens.guidance.trace.capture.RegressionManifest
import com.sailens.guidance.trace.capture.RegressionRevokeRecord
import com.sailens.guidance.trace.capture.RegressionStats
import com.sailens.guidance.trace.capture.RegressionUtteranceRecord
import com.sailens.guidance.trace.capture.UtteranceRecordEvents
import com.sailens.guidance.trace.capture.WindowFrameRecord
import com.sailens.guidance.trace.capture.WindowTriggers
import com.sailens.guidance.trace.regression.RegressionFrameAssembler
import com.sailens.guidance.trace.regression.RegressionRecorder
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Sizes and windows of the model-regression record. */
internal data class RegressionCaptureConfig(
    /** Frames waiting for the writer before the record starts losing them. */
    val queueCapacity: Int = 240,
    /**
     * Speech-lifecycle events waiting for the writer. A queue of their own, so that they can never
     * cost the record a frame (and with it exact replay); one that does not fit is counted dropped.
     */
    val utteranceQueueCapacity: Int = 1024,
    /** Class-map copies in flight between the pipeline and the writer. */
    val classMapPoolSize: Int = 16,
    /** Full-resolution frames kept for event windows: 10 Hz, 2.5 s. */
    val windowIntervalNanos: Long = 100_000_000,
    val windowRingSize: Int = 25,
    val promptWindowBeforeNanos: Long = 1_000_000_000,
    val promptWindowAfterNanos: Long = 500_000_000,
    val markerWindowBeforeNanos: Long = 2_000_000_000,
    val windowJpegQuality: Int = 90,
)

/**
 * Begins and ends the speech side's lifecycle sessions (det-first plan D1.3) with the record's own:
 * what the speech engine reports is attributed to the session its utterance was submitted in.
 */
internal interface SpeechLifecycleSessions {
    fun begin(sessionId: String)

    /** Synchronous: the session's attempts still without an outcome are reported before it returns. */
    fun end(sessionId: String)
}

/**
 * Records the model-regression part of a `model_regression` capture (M0b): one
 * [RegressionFrameRecord] per processed frame into `regression.jsonl`, class maps into
 * `classmaps.bin`, revocations, the speech lifecycle ([RegressionUtteranceRecord]) when
 * [speechSessions] is wired, and full-resolution frames around delivered prompts and markers.
 *
 * Lifecycle. [startSession] runs synchronously when the trace session starts, before Guidance
 * processes its first frame -- replay starts from the session's initial state, so the first frame
 * must not be missed. The session directory is created later by [FieldCaptureController]; records
 * wait in a bounded queue until [attach]. [finish] stops recording, drains the queue and returns the
 * counters for the manifest; [abort] stops without writing what is left.
 *
 * Observation only: every hook is cheap on the calling thread (copies, CRCs), never blocks, never
 * throws into Guidance. What cannot be kept is counted as lost, and the first lost frame is where
 * exact replay ends.
 */
internal class RegressionCapture(
    private val manifestBase: () -> RegressionManifest,
    private val encoder: JpegEncoder,
    private val clock: CaptureClock,
    private val log: LogService,
    private val writerDispatcher: CoroutineDispatcher,
    private val config: RegressionCaptureConfig = RegressionCaptureConfig(),
    /** Null records no speech lifecycle, and the manifest says so. */
    private val speechSessions: SpeechLifecycleSessions? = null,
) : RegressionRecorder {

    @Volatile
    private var session: Session? = null

    override val isRecording: Boolean get() = session?.recording == true

    /** Bytes the active session's record wrote so far, for the storage cap. */
    val bytesWritten: Long get() = session?.bytes?.get() ?: 0

    /** Starts recording for [sessionId]; called on the trace thread before the first frame. */
    fun startSession(sessionId: String) {
        session?.let { abandon(it, "superseded by $sessionId") }
        session = Session(sessionId)
        runCatching { speechSessions?.begin(sessionId) }
    }

    /** The manifest block written when the capture starts; counters are filled in by [finish]. */
    fun manifestBlock(): RegressionManifest = manifestBase().copy(utteranceLifecycle = speechSessions != null)

    /** The session directory exists now: the writer may start. Writer-thread caller. */
    fun attach(sessionId: String, directory: File) {
        val s = session?.takeIf { it.id == sessionId } ?: return
        s.writerJob = s.scope.launch { s.write(directory) }
    }

    /** Stops recording, writes everything queued and returns the counters. */
    suspend fun finish(sessionId: String): RegressionStats? {
        val s = session?.takeIf { it.id == sessionId } ?: return null
        // Before recording stops: what the speech side still owes the session is reported now.
        runCatching { speechSessions?.end(sessionId) }
        s.recording = false
        s.queue.close()
        s.utterances.close()
        val writer = s.writerJob
        if (writer == null) {
            s.discardQueued("the capture never started writing")
        } else {
            writer.join()
            // Anything the writer left behind (it failed) is lost, and counted.
            s.discardQueued("the writer stopped")
        }
        if (session === s) session = null
        return s.stats()
    }

    /** Stops recording without writing what is queued (the capture failed). */
    fun abort(sessionId: String, reason: String): RegressionStats? {
        val s = session?.takeIf { it.id == sessionId } ?: return null
        abandon(s, reason)
        if (session === s) session = null
        return s.stats()
    }

    private fun abandon(s: Session, reason: String) {
        runCatching { speechSessions?.end(s.id) }
        s.recording = false
        s.writerJob?.cancel()
        s.queue.close()
        s.utterances.close()
        s.discardQueued(reason)
        log.warning(TAG, "Regression record stopped: $reason")
    }

    // ---- triggers for event windows -----------------------------------------------------------

    /**
     * A prompt outcome from the trace; a delivered one opens a window around its offering frame.
     * One handed to the screen reader is noted with the lifecycle: that path reports none.
     */
    fun onPromptOutcome(outcome: PromptOutcomeTrace) {
        if (outcome.deliveredAt == null) return
        session?.windows?.onPrompt(outcome.sourceSequenceNumber, outcome.eventId)
        if (PromptDeliveryChannels.SCREEN_READER in outcome.deliveredVia) {
            val s = session ?: return
            onUtterance(
                s.id,
                RegressionUtteranceRecord(
                    event = UtteranceRecordEvents.SCREEN_READER_PUBLISHED,
                    attemptId = outcome.eventId,
                    logicalId = outcome.eventId,
                    elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                ),
            )
        }
    }

    /** A missed-alert marker, at its observed elapsed-realtime time. */
    fun onMarker(elapsedRealtimeNanos: Long) {
        session?.windows?.onMarker(elapsedRealtimeNanos)
    }

    // ---- RegressionRecorder hooks (Guidance threads) --------------------------------------------

    // Every hook runs on a Guidance thread and must never throw into it: a failure here costs the
    // record a frame (counted), never Guidance a frame.

    override fun beginFrame(frame: ImageFrame) = guarded("beginFrame") { it.beginFrame(frame) }

    override fun onClock(component: String, value: Long) = guarded("onClock") { it.onClock(component, value) }

    override fun onSemantic(frame: ImageFrame, ran: Boolean, output: SegmentationOutput?, failure: Throwable?) =
        guarded("onSemantic") { it.onSemantic(frame, ran, output, failure) }

    override fun onDetection(frame: ImageFrame, detections: List<ObstacleDetection>?, failure: Throwable?) =
        guarded("onDetection") { it.onDetection(frame, detections, failure) }

    override fun onConnectivity(passableMask: BinaryMask, stats: ConnectivityStats?) =
        guarded("onConnectivity") { it.onConnectivity(passableMask, stats) }

    override fun onFrameQuality(result: FrameQualityResult) = guarded("onFrameQuality") { it.onFrameQuality(result) }

    override fun onGeometry(geometry: CameraGeometry) = guarded("onGeometry") { it.onGeometry(geometry) }

    override fun onStationary(stationary: Boolean) = guarded("onStationary") { it.onStationary(stationary) }

    override fun endFrame(frame: ImageFrame, events: List<SceneEvent>, failure: Throwable?) =
        guarded("endFrame") { it.endFrame(frame, events, failure) }

    override fun onRevoke(eventId: UUID, decisionsBefore: Long) = guarded("onRevoke") { it.onRevoke(eventId, decisionsBefore) }

    // Not guarded(): a lost lifecycle event is counted against the lifecycle, never against a frame.
    override fun onUtterance(sessionId: String?, record: RegressionUtteranceRecord) {
        val s = session?.takeIf { it.recording && it.id == sessionId } ?: return
        try {
            if (s.utterances.trySend(record).isFailure) s.utteranceEventsDropped.incrementAndGet()
        } catch (e: Exception) {
            s.utteranceEventsDropped.incrementAndGet()
        }
    }

    private inline fun guarded(hook: String, block: (Session) -> Unit) {
        val s = session?.takeIf { it.recording } ?: return
        try {
            block(s)
        } catch (e: Exception) {
            s.hookFailed(hook, e)
        }
    }

    // ---- one session ----------------------------------------------------------------------------

    private sealed interface Item {
        class Frame(val record: RegressionFrameRecord, val classIds: IntArray?, val width: Int, val height: Int) : Item
        class Revoke(val record: RegressionRevokeRecord) : Item
        class Window(val copy: YuvCopy, val trigger: String, val triggerRef: String?) : Item
    }

    private inner class Session(val id: String) {
        @Volatile var recording = true
        val scope = CoroutineScope(SupervisorJob() + writerDispatcher)
        var writerJob: Job? = null
        val queue = Channel<Item>(config.queueCapacity)
        val utterances = Channel<RegressionUtteranceRecord>(config.utteranceQueueCapacity)
        val windows = WindowKeeper()

        private val classMapPool = ArrayDeque<IntArray>()
        private var classMapsInFlight = 0
        private val assembler = RegressionFrameAssembler(::borrowClassMap)

        /** The last frame that began; a lost revoke ends exact replay there. */
        @Volatile private var lastSeq = 0L

        val framesRecorded = AtomicLong()
        val framesLost = AtomicLong()
        @Volatile var firstLostSeq: Long? = null
        val classMapBytes = AtomicLong()
        val windowsRecorded = AtomicLong()
        val windowFramesStored = AtomicLong()
        val windowFramesDropped = AtomicLong()
        val utteranceEventsRecorded = AtomicLong()
        val utteranceEventsDropped = AtomicLong()
        val bytes = AtomicLong()
        private var strayClockReads = 0L

        fun stats() = RegressionStats(
            framesRecorded = framesRecorded.get(),
            framesLost = framesLost.get(),
            firstLostSeq = firstLostSeq,
            classMapBytes = classMapBytes.get(),
            windowsRecorded = windowsRecorded.get(),
            windowFramesStored = windowFramesStored.get(),
            windowFramesDropped = windowFramesDropped.get(),
            utteranceEventsRecorded = utteranceEventsRecorded.get(),
            utteranceEventsDropped = utteranceEventsDropped.get(),
        )

        private var hookFailures = 0L

        /** A hook threw: the frame being recorded cannot be complete. */
        fun hookFailed(hook: String, e: Exception) {
            if (hookFailures++ == 0L) log.warning(TAG, "Regression hook $hook failed", throwable = e)
            if (!assembler.markIncomplete("$hook failed: ${e.javaClass.simpleName}")) lose(lastSeq, "$hook failed")
        }

        fun lose(seq: Long, why: String) {
            framesLost.incrementAndGet()
            if (firstLostSeq == null) {
                firstLostSeq = seq
                log.warning(TAG, "Regression record lost frame $seq ($why); replay is exact only before it")
            }
        }

        fun beginFrame(frame: ImageFrame) {
            lastSeq = frame.sequenceNumber
            assembler.beginFrame(frame)?.let(::giveUp)
            windows.offer(frame)
        }

        fun onClock(component: String, value: Long) {
            if (!assembler.onClock(component, value) && strayClockReads++ == 0L) {
                log.warning(TAG, "A clock was read outside a frame ($component)")
            }
        }

        fun onSemantic(frame: ImageFrame, ran: Boolean, output: SegmentationOutput?, failure: Throwable?) =
            assembler.onSemantic(frame, ran, output, failure)

        fun onDetection(frame: ImageFrame, detections: List<ObstacleDetection>?, failure: Throwable?) =
            assembler.onDetection(frame, detections, failure)

        fun onConnectivity(passableMask: BinaryMask, stats: ConnectivityStats?) = assembler.onConnectivity(passableMask, stats)

        fun onFrameQuality(result: FrameQualityResult) = assembler.onFrameQuality(result)

        fun onGeometry(geometry: CameraGeometry) = assembler.onGeometry(geometry)

        fun onStationary(stationary: Boolean) = assembler.onStationary(stationary)

        fun endFrame(frame: ImageFrame, events: List<SceneEvent>, failure: Throwable?) {
            when (val outcome = assembler.endFrame(frame, events, failure)) {
                is RegressionFrameAssembler.Outcome.Lost -> giveUp(outcome)
                is RegressionFrameAssembler.Outcome.Frame -> {
                    val item = Item.Frame(outcome.record, outcome.classIds, outcome.width, outcome.height)
                    if (queue.trySend(item).isFailure) {
                        outcome.classIds?.let(::returnClassMap)
                        lose(outcome.seq, "writer queue full")
                    }
                }
            }
        }

        private fun giveUp(lost: RegressionFrameAssembler.Outcome.Lost) {
            lost.classIds?.let(::returnClassMap)
            lose(lost.seq, lost.why)
        }

        fun onRevoke(eventId: UUID, decisionsBefore: Long) {
            val record = RegressionRevokeRecord(eventId.toString(), decisionsBefore, clock.elapsedRealtimeNanos())
            if (queue.trySend(Item.Revoke(record)).isFailure) {
                // A lost revoke changes later decisions as surely as a lost frame does.
                lose(lastSeq, "revoke of $eventId could not be queued")
            }
        }

        private fun borrowClassMap(size: Int): IntArray? = synchronized(classMapPool) {
            val pooled = classMapPool.removeFirstOrNull()?.takeIf { it.size >= size }
            when {
                pooled != null -> pooled
                classMapsInFlight < config.classMapPoolSize -> IntArray(size).also { classMapsInFlight++ }
                else -> null
            }
        }

        fun returnClassMap(ids: IntArray) {
            synchronized(classMapPool) { classMapPool.addLast(ids) }
        }

        fun discardQueued(reason: String) {
            while (true) {
                val item = queue.tryReceive().getOrNull() ?: break
                when (item) {
                    is Item.Frame -> {
                        item.classIds?.let(::returnClassMap)
                        lose(item.record.seq, reason)
                    }
                    is Item.Revoke -> lose(lastSeq, reason)
                    is Item.Window -> {
                        windowFramesDropped.incrementAndGet()
                        windows.release(item.copy)
                    }
                }
            }
            while (utterances.tryReceive().getOrNull() != null) utteranceEventsDropped.incrementAndGet()
        }

        /** Writer: every lifecycle event waiting now, appended to `regression.jsonl`. */
        private fun writeUtterances(regression: BufferedWriter) {
            while (true) {
                val record = utterances.tryReceive().getOrNull() ?: return
                try {
                    val line = CaptureSchema.encodeRecord(record)
                    regression.write(line)
                    regression.write("\n")
                    bytes.addAndGet(line.length + 1L)
                    utteranceEventsRecorded.incrementAndGet()
                } catch (e: Exception) {
                    utteranceEventsDropped.incrementAndGet()
                }
            }
        }

        /** Writer thread: drains [queue] into the session directory until it is closed. */
        suspend fun write(directory: File) {
            try {
                writeAll(directory)
            } catch (e: Exception) {
                log.warning(TAG, "Regression writer failed", throwable = e)
            }
        }

        private suspend fun writeAll(directory: File) {
            val windowsDir = File(directory, CaptureSchema.WINDOWS_DIR)
            val classMaps = FileOutputStream(File(directory, CaptureSchema.CLASS_MAPS_FILE), true)
            var offset = File(directory, CaptureSchema.CLASS_MAPS_FILE).length()
            val regression = BufferedWriter(OutputStreamWriter(FileOutputStream(File(directory, CaptureSchema.REGRESSION_FILE), true), Charsets.UTF_8))
            val windowLines = BufferedWriter(OutputStreamWriter(FileOutputStream(File(directory, CaptureSchema.WINDOWS_FILE), true), Charsets.UTF_8))
            val classMapEncoder = ClassMapStore.Encoder()
            try {
                for (item in queue) {
                    try {
                        when (item) {
                            is Item.Frame -> {
                                var record = item.record
                                item.classIds?.let { ids ->
                                    val entry = classMapEncoder.encode(record.seq, item.width, item.height, ids)
                                    returnClassMap(ids)
                                    classMaps.write(entry.bytes)
                                    record = record.copy(semantic = record.semantic?.copy(classMap = entry.ref(offset)))
                                    offset += entry.bytes.size
                                    classMapBytes.addAndGet(entry.bytes.size.toLong())
                                    bytes.addAndGet(entry.bytes.size.toLong())
                                }
                                val line = CaptureSchema.encodeRecord(record)
                                regression.write(line)
                                regression.write("\n")
                                bytes.addAndGet(line.length + 1L)
                                framesRecorded.incrementAndGet()
                            }
                            is Item.Revoke -> {
                                val line = CaptureSchema.encodeRecord(item.record)
                                regression.write(line)
                                regression.write("\n")
                                bytes.addAndGet(line.length + 1L)
                            }
                            is Item.Window -> {
                                windowsDir.mkdirs()
                                val name = "%06d.jpg".format(item.copy.seq)
                                val jpeg = encoder.encode(item.copy.toNv21(), config.windowJpegQuality)
                                File(windowsDir, name).writeBytes(jpeg)
                                val line = CaptureSchema.encodeRecord(
                                    WindowFrameRecord(
                                        seq = item.copy.seq,
                                        sensorTimestampNanos = item.copy.sensorTimestampNanos,
                                        trigger = item.trigger,
                                        triggerRef = item.triggerRef,
                                        encoding = FrameEncoding.JPEG,
                                        file = "${CaptureSchema.WINDOWS_DIR}/$name",
                                        width = item.copy.width,
                                        height = item.copy.height,
                                        rotationDegrees = item.copy.rotationDegrees,
                                    ),
                                )
                                windowLines.write(line)
                                windowLines.write("\n")
                                bytes.addAndGet(jpeg.size + line.length + 1L)
                                windowFramesStored.incrementAndGet()
                                windows.release(item.copy)
                            }
                        }
                    } catch (e: Exception) {
                        when (item) {
                            is Item.Frame -> {
                                item.classIds?.let(::returnClassMap)
                                lose(item.record.seq, "write failed: ${e.message}")
                            }
                            is Item.Revoke -> lose(lastSeq, "write failed: ${e.message}")
                            is Item.Window -> {
                                windowFramesDropped.incrementAndGet()
                                windows.release(item.copy)
                            }
                        }
                    }
                    writeUtterances(regression)
                    if (queue.isEmpty) {
                        regression.flush()
                        windowLines.flush()
                    }
                }
                // The queue is closed: what the speech side reported last is written too.
                writeUtterances(regression)
                regression.flush()
            } finally {
                runCatching { regression.close() }
                runCatching { windowLines.close() }
                runCatching { classMaps.close() }
                classMapEncoder.close()
            }
        }

        /**
         * Full-resolution frames for event windows. Frames are copied (planes only, no conversion)
         * into a ring at [RegressionCaptureConfig.windowIntervalNanos]; a trigger picks the ring
         * entries inside its window once the window has passed, and only those are encoded, on the
         * writer thread. A copy queued for encoding is pinned until the writer releases it.
         */
        inner class WindowKeeper {
            private val ring = ArrayDeque<YuvCopy>()
            private val free = ArrayDeque<YuvCopy>()
            private var lastCopiedNanos: Long? = null
            private val recentTimestamps = LinkedHashMap<Long, Long>()
            private val pending = mutableListOf<PendingWindow>()

            private inner class PendingWindow(
                val fromNanos: Long,
                val toNanos: Long,
                val receiptClock: Boolean,
                val trigger: String,
                val ref: String?,
            )

            fun offer(frame: ImageFrame) = synchronized(this) {
                recentTimestamps[frame.sequenceNumber] = frame.timestamp
                while (recentTimestamps.size > RECENT_TIMESTAMPS) recentTimestamps.remove(recentTimestamps.keys.first())
                val last = lastCopiedNanos
                if (last == null || frame.timestamp - last >= config.windowIntervalNanos) {
                    val evicted = if (ring.size >= config.windowRingSize) ring.removeFirst() else null
                    // An evicted copy still queued for encoding is not reused now; the writer hands
                    // it to [free] when it is done with it.
                    val reuse = evicted?.takeUnless { it.pinned } ?: free.removeFirstOrNull()
                    YuvCopy.copy(frame, reuse)?.let { copy ->
                        ring.addLast(copy)
                        lastCopiedNanos = frame.timestamp
                    }
                }
                flushDue(frame)
            }

            fun onPrompt(sourceSeq: Long, eventId: String) = synchronized(this) {
                val t = recentTimestamps[sourceSeq] ?: return@synchronized
                pending += PendingWindow(t - config.promptWindowBeforeNanos, t + config.promptWindowAfterNanos, false, WindowTriggers.DELIVERED_PROMPT, eventId)
                windowsRecorded.incrementAndGet()
            }

            fun onMarker(elapsedRealtimeNanos: Long) = synchronized(this) {
                pending += PendingWindow(elapsedRealtimeNanos - config.markerWindowBeforeNanos, elapsedRealtimeNanos, true, WindowTriggers.MARKER, elapsedRealtimeNanos.toString())
                windowsRecorded.incrementAndGet()
            }

            /** Hands over the frames of every window that has passed by [frame]. */
            private fun flushDue(frame: ImageFrame) {
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val window = iterator.next()
                    val now = if (window.receiptClock) frame.receivedElapsedRealtimeNanos else frame.timestamp
                    if (now < window.toNanos) continue
                    iterator.remove()
                    for (copy in ring) {
                        val t = if (window.receiptClock) copy.receivedElapsedRealtimeNanos else copy.sensorTimestampNanos
                        if (t < window.fromNanos || t > window.toNanos || copy.pinned || copy.written) continue
                        copy.pinned = true
                        copy.written = true
                        if (queue.trySend(Item.Window(copy, window.trigger, window.ref)).isFailure) {
                            copy.pinned = false
                            windowFramesDropped.incrementAndGet()
                        }
                    }
                }
            }

            /** Writer thread: the copy is encoded; its buffer may be reused. */
            fun release(copy: YuvCopy) = synchronized(this) {
                copy.pinned = false
                if (copy !in ring) free.addLast(copy)
            }
        }
    }

    private companion object {
        const val TAG = "RegressionCapture"
        const val RECENT_TIMESTAMPS = 600
    }
}

/**
 * The planes of one frame, copied as they are (strides kept) so the copy costs only arraycopy on
 * Guidance's thread; conversion to NV21 happens on the writer, for the frames a window needs.
 */
internal class YuvCopy private constructor(
    var seq: Long,
    var sensorTimestampNanos: Long,
    var receivedElapsedRealtimeNanos: Long,
    var width: Int,
    var height: Int,
    var rotationDegrees: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    var yRowStride: Int,
    var yPixelStride: Int,
    var uRowStride: Int,
    var uPixelStride: Int,
    var vRowStride: Int,
    var vPixelStride: Int,
) {
    @Volatile var pinned = false
    @Volatile var written = false

    fun toNv21(): Nv21Image {
        val frame = ImageFrame(
            width = width,
            height = height,
            pixelBytes = ByteArray(0),
            pixelFormat = com.sailens.core.frame.ImagePixelFormat.YUV_420_888,
            timestamp = sensorTimestampNanos,
            rotationDegrees = rotationDegrees,
            sequenceNumber = seq,
            yuvData = Yuv420FrameData(
                y = YuvPlaneData(y, yRowStride, yPixelStride),
                u = YuvPlaneData(u, uRowStride, uPixelStride),
                v = YuvPlaneData(v, vRowStride, vPixelStride),
            ),
        )
        return requireNotNull(Nv21Downscaler.downscale(frame, maxOf(width, height)))
    }

    companion object {
        /** Copies [frame]'s planes into [reuse] when it fits, else into a new copy; null without planes. */
        fun copy(frame: ImageFrame, reuse: YuvCopy?): YuvCopy? {
            val yuv = frame.yuvData ?: return null
            val target = reuse?.takeIf { it.y.size >= yuv.y.bytes.size && it.u.size >= yuv.u.bytes.size && it.v.size >= yuv.v.bytes.size }
                ?: YuvCopy(
                    0, 0, 0, 0, 0, 0,
                    ByteArray(yuv.y.bytes.size), ByteArray(yuv.u.bytes.size), ByteArray(yuv.v.bytes.size),
                    0, 0, 0, 0, 0, 0,
                )
            System.arraycopy(yuv.y.bytes, 0, target.y, 0, yuv.y.bytes.size)
            System.arraycopy(yuv.u.bytes, 0, target.u, 0, yuv.u.bytes.size)
            System.arraycopy(yuv.v.bytes, 0, target.v, 0, yuv.v.bytes.size)
            target.seq = frame.sequenceNumber
            target.sensorTimestampNanos = frame.timestamp
            target.receivedElapsedRealtimeNanos = frame.receivedElapsedRealtimeNanos
            target.width = frame.width
            target.height = frame.height
            target.rotationDegrees = frame.rotationDegrees
            target.yRowStride = yuv.y.rowStride
            target.yPixelStride = yuv.y.pixelStride
            target.uRowStride = yuv.u.rowStride
            target.uPixelStride = yuv.u.pixelStride
            target.vRowStride = yuv.v.rowStride
            target.vPixelStride = yuv.v.pixelStride
            target.pinned = false
            target.written = false
            return target
        }
    }
}
