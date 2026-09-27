package com.sailens.shell.capture

import com.sailens.camera.CameraCharacteristicsProvider
import com.sailens.camera.CameraCharacteristicsSnapshot
import com.sailens.camera.CameraTimestampSource
import com.sailens.camera.FrameSource
import com.sailens.camera.PixelRect
import com.sailens.core.frame.ImageFrame
import com.sailens.core.log.LogService
import com.sailens.guidance.trace.capture.AnchorReason
import com.sailens.guidance.trace.capture.CaptureCameraRecord
import com.sailens.guidance.trace.capture.CaptureManifest
import com.sailens.guidance.trace.capture.CaptureModes
import com.sailens.guidance.trace.capture.CaptureStats
import com.sailens.guidance.trace.capture.ClockAnchorRecord
import com.sailens.guidance.trace.capture.FrameEncoding
import com.sailens.guidance.trace.capture.FrameRecord
import com.sailens.guidance.trace.capture.MarkerKind
import com.sailens.guidance.trace.capture.MarkerRecord
import com.sailens.guidance.trace.capture.MarkerSource
import com.sailens.guidance.trace.capture.SensorRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.sailens.guidance.trace.capture.CaptureSchema
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Wall clock and elapsed-realtime clock, injectable for tests. */
internal interface CaptureClock {
    fun wallMs(): Long
    fun elapsedRealtimeNanos(): Long
}

internal fun interface JpegEncoder {
    fun encode(image: Nv21Image, quality: Int): ByteArray
}

/** Motion sensors for one session. [start] returns the names of the sensors that registered. */
internal interface CaptureSensorSource {
    fun start(onRecord: (SensorRecord) -> Unit): List<String>
    fun stop()
}

internal data class CaptureDeviceInfo(
    val appVersionName: String?,
    val appVersionCode: Long?,
    val gitSha: String?,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
)

/** One row of the capture list. */
internal data class CaptureSummary(
    val sessionId: String,
    val startedWallMs: Long,
    val endedWallMs: Long?,
    val bytes: Long,
    val framesStored: Long,
    val markers: Long,
    val complete: Boolean,
    val failureReason: String?,
    val pinned: Boolean,
    val exported: Boolean,
    val active: Boolean,
)

internal data class FieldCaptureConfig(
    val frameIntervalMs: Long = 200,
    val maxLongSide: Int = 640,
    val jpegQuality: Int = 80,
    /** Frames waiting for the encoder. Small on purpose: a late frame is dropped, not queued. */
    val encodeQueueCapacity: Int = 1,
    val sensorQueueCapacity: Int = 4096,
    val anchorIntervalMs: Long = 30_000,
    val timingSync: TimingSyncConfig = TimingSyncConfig(),
)

/**
 * The timing-sync burst ([CaptureModes.TIMING_SYNC]): every frame the camera delivers, as small raw
 * luma, for [durationMs]; then the capture ends on its own while Guidance carries on. It exists to
 * measure frame-to-gyroscope alignment and changes the device load, so it is never a baseline.
 */
internal data class TimingSyncConfig(
    val durationMs: Long = 15_000,
    val maxLongSide: Int = 400,
    /** A little slack at camera rate; a frame dropped here is counted, one dropped at the source shows as a gap. */
    val queueCapacity: Int = 4,
)

/**
 * Field capture for one Guidance session at a time (M0a, implementation §4), in one of two modes:
 * [CaptureModes.FIELD_EVIDENCE] (reduced JPEG frames for the whole session) or
 * [CaptureModes.TIMING_SYNC] (a short burst of raw luma at camera rate).
 *
 * Rules this class exists to keep:
 * - **Capture never fails or blocks Guidance.** The session hooks only read a flag and hand work to
 *   the capture's own thread; every failure inside capture ends as a failed, incomplete capture on
 *   disk and a log line, never as an exception to the caller.
 * - **The mode is read once**, when a session starts ([sessionMode]; null means no capture).
 * - **Frames are borrowed briefly.** Each frame is downscaled into capture's own buffer and
 *   released to the frame source immediately, whatever happens next.
 * - **Nothing piles up.** At most [FieldCaptureConfig.encodeQueueCapacity] frames wait for the
 *   encoder; a newer frame replaces a waiting one and the drop is counted.
 * - **One writer thread.** All file writes happen on [writerDispatcher], so the writer needs no locks.
 */
internal class FieldCaptureController(
    private val root: File,
    private val sessionMode: () -> String?,
    private val frameSource: FrameSource,
    private val cameraFacts: CameraCharacteristicsProvider,
    private val sensors: CaptureSensorSource,
    private val encoder: JpegEncoder,
    private val clock: CaptureClock,
    private val deviceInfo: CaptureDeviceInfo,
    private val log: LogService,
    private val config: FieldCaptureConfig = FieldCaptureConfig(),
    private val writerDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "field-capture") }
            .asCoroutineDispatcher(),
    private val frameDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val retention: CaptureRetention = CaptureRetention(root),
    private val exporter: CaptureExporter? = null,
    /** Heavy session management (listing, ZIP export): never on the writer thread. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + writerDispatcher + CoroutineExceptionHandler { _, throwable ->
            log.warning(TAG, "Field capture task failed", throwable = throwable)
        },
    )

    /** Confined to [writerDispatcher]. */
    private var active: ActiveCapture? = null
        set(value) {
            field = value
            observable = value
            _isCapturing.value = value != null
        }

    /**
     * The same capture as [active], readable from any thread for observing a marker. Only its
     * immutable id and its volatile last stored frame are read through it.
     */
    @Volatile
    private var observable: ActiveCapture? = null

    /** Sessions being exported right now; retention and delete leave them alone. Writer thread only. */
    private val busySessions = mutableSetOf<String>()

    /** Serialises capture start and end; see [end]. */
    private val lifecycle = Mutex()

    private val _isCapturing = MutableStateFlow(false)

    /** Whether a capture is recording right now (for the marker button and the Volume-Down key). */
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isManagingFiles = MutableStateFlow(false)

    /**
     * Whether heavy file work (a ZIP export) is running. Capture and heavy file work never overlap,
     * in either direction: exporting is refused while recording, and Guidance is held off while
     * this is true ([FieldCaptureStartGate]); a capture that starts anyway ends at once as
     * [EXPORT_IN_PROGRESS].
     */
    val isManagingFiles: StateFlow<Boolean> = _isManagingFiles.asStateFlow()

    /** Writer thread. */
    private fun setBusy(sessionId: String, busy: Boolean): Boolean {
        val changed = if (busy) busySessions.add(sessionId) else busySessions.remove(sessionId)
        _isManagingFiles.value = busySessions.isNotEmpty()
        return changed
    }

    /** Bytes held by the sessions other than the active one, as of the last retention pass. */
    private var otherSessionsBytes = 0L

    init {
        // A maintenance opportunity at start-up, whether or not capture is switched on, so expired
        // captures do not stay on the device just because nobody records any more. Stale export
        // ZIPs go too.
        scope.launch {
            maintain()
            runCatching { exporter?.clearStaleExports(clock.wallMs()) }
        }
    }

    /**
     * Runs retention now and clears stale export ZIPs -- called when the capture list opens. Never
     * throws.
     */
    fun runMaintenance(): Job = scope.launch {
        maintain()
        runCatching { exporter?.clearStaleExports(clock.wallMs()) }
    }

    // ---- session management (capture list) ------------------------------------------------------
    //
    // Rule: only light mutations that must be atomic with capture state run on the writer thread.
    // Heavy I/O (scanning directories, building a ZIP) never does, so it cannot stall a recording.
    // While a capture is recording, finished sessions are read-only: managing data waits until the
    // recording ends, so the tool cannot disturb the evidence it is collecting.

    /** Scans the capture directory off the writer thread; a directory deleted meanwhile is skipped. */
    suspend fun listSessions(): List<CaptureSummary> = withContext(ioDispatcher) {
        val activeId = observable?.sessionId
        root.listFiles { file -> file.isDirectory }.orEmpty()
            .mapNotNull { directory -> runCatching { summarize(directory, directory.name == activeId) }.getOrNull() }
            .sortedByDescending { it.startedWallMs }
    }

    /** Keeps or stops keeping a finished session. Refused while recording, or for an unreadable one. */
    suspend fun setPinned(sessionId: String, pinned: Boolean): Boolean = onWriter {
        if (active != null || sessionId in busySessions) return@onWriter false
        editManifest(sessionId) { it.copy(pinned = pinned) }
    }

    /** Deletes a finished session. Refused while recording and while it is being exported. */
    suspend fun delete(sessionId: String): Boolean = onWriter {
        if (active != null || sessionId in busySessions) return@onWriter false
        File(root, sessionId).deleteRecursively().also { maintain() }
    }

    /**
     * Packs a finished session into a ZIP for sharing and marks it exported (which also keeps it past
     * the age limit). Refused while recording. The ZIP is built off the writer thread; the session
     * is protected from retention and delete meanwhile. Throws when exporting is refused or fails --
     * including when the ZIP was built but the session could not be marked exported, so a ZIP is
     * never shared from a session that would still expire.
     */
    suspend fun export(sessionId: String): File {
        val zipper = exporter ?: throw IOException("export is not available")
        onWriter {
            if (active != null) throw IOException("a capture is recording; export after it ends")
            if (!setBusy(sessionId, true)) throw IOException("already exporting")
        }
        val zip = try {
            withContext(ioDispatcher) { zipper.export(sessionId) }
        } catch (e: Throwable) {
            withContext(NonCancellable) { onWriter { setBusy(sessionId, false) } }
            throw e
        }
        val marked = withContext(NonCancellable) {
            onWriter {
                setBusy(sessionId, false)
                runCatching { editManifest(sessionId) { it.copy(exportedAtWallMs = clock.wallMs()) } }.getOrDefault(false)
            }
        }
        if (!marked) {
            zip.delete()
            throw IOException("the capture's manifest could not be updated")
        }
        return zip
    }

    private fun summarize(directory: File, active: Boolean): CaptureSummary {
        val manifest = runCatching {
            CaptureSchema.json.decodeFromString(
                CaptureManifest.serializer(),
                File(directory, CaptureSchema.MANIFEST_FILE).readText(),
            )
        }.getOrNull()
        return CaptureSummary(
            sessionId = directory.name,
            startedWallMs = manifest?.startedWallMs ?: directory.lastModified(),
            endedWallMs = manifest?.endedWallMs,
            bytes = directory.walkBottomUp().filter { it.isFile }.sumOf { it.length() },
            framesStored = manifest?.stats?.framesEncoded ?: 0,
            markers = manifest?.stats?.markers ?: 0,
            complete = manifest?.complete ?: false,
            failureReason = if (manifest == null) "unreadable manifest" else manifest.failureReason,
            pinned = manifest?.pinned ?: false,
            exported = manifest?.exportedAtWallMs != null,
            active = active,
        )
    }

    /** Writer thread; never for the active session (callers refuse while recording). */
    private fun editManifest(sessionId: String, edit: (CaptureManifest) -> CaptureManifest): Boolean {
        val file = File(File(root, sessionId), CaptureSchema.MANIFEST_FILE)
        val manifest = runCatching {
            CaptureSchema.json.decodeFromString(CaptureManifest.serializer(), file.readText())
        }.getOrNull() ?: return false
        writeAtomically(file, CaptureSchema.encodeManifest(edit(manifest)))
        return true
    }

    private suspend fun <T> onWriter(block: () -> T): T = withContext(scope.coroutineContext) { block() }

    /** Called when a trace session starts. Never throws, never blocks. */
    fun onSessionStarted(sessionId: String, targetHardwareProfile: String?): Job? {
        val mode = runCatching(sessionMode).getOrNull() ?: return null
        if (mode != CaptureModes.FIELD_EVIDENCE && mode != CaptureModes.TIMING_SYNC) {
            log.warning(TAG, "Unknown capture mode '$mode'; not capturing")
            return null
        }
        return scope.launch { begin(sessionId, mode, targetHardwareProfile) }
    }

    /** Called when the trace session finishes. Never throws, never blocks. */
    fun onSessionFinished(): Job = scope.launch { end() }

    // ---- missed-alert markers ------------------------------------------------------------------

    /**
     * Fixes a marker at the moment of the press, on the caller's (input) thread: the time, the
     * session it belongs to, and the newest frame already stored. Writing it is separate and may
     * happen later, when the writer is free -- it never decides when the event happened. Returns null
     * when nothing is recording.
     */
    fun observeMarker(source: MarkerSource): MarkerObservation? {
        val capture = observable ?: return null
        return MarkerObservation(
            sessionId = capture.sessionId,
            wallMs = clock.wallMs(),
            elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
            lastStoredFrameSeq = capture.lastStoredFrameSeq,
            source = source,
        )
    }

    /**
     * Persists an observed marker. Returns whether it was written, so the press is confirmed only
     * then; a marker whose session has ended (or failed) meanwhile is dropped.
     */
    suspend fun writeMarker(observation: MarkerObservation): Boolean {
        val result = CompletableDeferred<Boolean>()
        scope.launch {
            val capture = active
            if (capture == null || capture.sessionId != observation.sessionId) {
                result.complete(false)
                return@launch
            }
            capture.guarded("marker") {
                capture.writer.append(
                    MarkerRecord(
                        kind = MarkerKind.MISSED_ALERT,
                        wallMs = observation.wallMs,
                        elapsedRealtimeNanos = observation.elapsedRealtimeNanos,
                        lastStoredFrameSeq = observation.lastStoredFrameSeq,
                        source = observation.source,
                    ),
                )
                capture.writer.flush()
                capture.markers.incrementAndGet()
            }
            result.complete(!capture.failed)
        }.invokeOnCompletion { if (!result.isCompleted) result.complete(false) }
        return result.await()
    }

    /** Observe and write in one call. */
    suspend fun recordMarker(source: MarkerSource): Boolean =
        observeMarker(source)?.let { writeMarker(it) } ?: false

    // ---- writer thread -------------------------------------------------------------------------

    private suspend fun begin(sessionId: String, mode: String, targetHardwareProfile: String?): Unit =
        lifecycle.withLock { beginLocked(sessionId, mode, targetHardwareProfile) }

    private suspend fun beginLocked(sessionId: String, mode: String, targetHardwareProfile: String?) {
        // A session that never finished (the app kept running but the hook was missed) is closed
        // as incomplete rather than silently mixed with the new one.
        active?.let { fail(it, "superseded by session $sessionId") }

        maintain(activeSessionId = sessionId, activeBytes = 0)

        val manifest = CaptureManifest(
            sessionId = sessionId,
            captureMode = mode,
            startedWallMs = clock.wallMs(),
            startedElapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
            appVersionName = deviceInfo.appVersionName,
            appVersionCode = deviceInfo.appVersionCode,
            gitSha = deviceInfo.gitSha,
            deviceManufacturer = deviceInfo.manufacturer,
            deviceModel = deviceInfo.model,
            sdkInt = deviceInfo.sdkInt,
            targetHardwareProfile = targetHardwareProfile,
            camera = runCatching { cameraFacts.currentSnapshot() }.getOrNull()?.toRecord(),
        )
        val writer = try {
            CaptureSessionWriter(File(root, sessionId), manifest)
        } catch (e: Exception) {
            log.warning(TAG, "Field capture could not start", throwable = e)
            return
        }
        val capture = ActiveCapture(writer, mode)
        active = capture
        if (busySessions.isNotEmpty()) {
            // The start gate should have held Guidance off; this is the backstop. An empty, failed
            // capture is left behind so the gap in the evidence is visible rather than silent.
            fail(capture, EXPORT_IN_PROGRESS)
            return
        }

        capture.guarded("start") {
            writer.append(anchor(AnchorReason.START))
            val available = runCatching { sensors.start { capture.sensorQueue.trySend(it) } }
                .onFailure { log.warning(TAG, "Capture sensors did not register", throwable = it) }
                .getOrDefault(emptyList())
            writer.updateManifest { it.copy(sensorsAvailable = available) }
        }
        if (capture.failed) return

        capture.sensorWriterJob = scope.launch {
            for (record in capture.sensorQueue) capture.guarded("sensor") { writeSensor(capture, record) }
        }
        capture.frameWriterJob = scope.launch {
            for (frame in capture.encodeQueue) {
                capture.guarded("frame") { writeFrame(capture, frame) }
                enforceStorageLimit(capture)
            }
        }
        capture.anchorJob = scope.launch {
            while (isActive) {
                delay(config.anchorIntervalMs)
                capture.guarded("anchor") {
                    writer.append(anchor(AnchorReason.PERIODIC))
                    writer.flush()
                    writer.updateManifest { it.copy(stats = capture.stats()) }
                }
                maintain()
                enforceStorageLimit(capture)
            }
        }
        capture.frameJob = scope.launch(frameDispatcher) { collectFrames(capture) }
        if (capture.timingSync) {
            // The burst ends on its own; the session's own end then finds nothing to stop.
            capture.burstJob = scope.launch {
                delay(config.timingSync.durationMs)
                end(only = capture)
            }
        }
    }

    /**
     * Retention pass. The active session and sessions being exported are never deleted, only
     * counted; the active size comes from the writer so that no directory walk is needed per frame.
     */
    private fun maintain(
        activeSessionId: String? = active?.sessionId,
        activeBytes: Long = active?.writer?.bytesWritten ?: 0,
    ) {
        runCatching { retention.prune(clock.wallMs(), activeSessionId, activeBytes, busySessions.toSet()) }
            .onSuccess { otherSessionsBytes = it.otherSessionsBytes }
            .onFailure { log.warning(TAG, "Capture retention failed", throwable = it) }
    }

    /**
     * The 2 GB cap is real, not a between-sessions tidy-up: when the active capture would take the
     * total over it, older unpinned sessions go first, and if that is not enough the capture ends
     * as incomplete with [STORAGE_LIMIT_REACHED] instead of writing until the disk is full.
     */
    private suspend fun enforceStorageLimit(capture: ActiveCapture) {
        if (capture.failed || !overLimit(capture)) return
        maintain()
        if (overLimit(capture)) fail(capture, STORAGE_LIMIT_REACHED)
    }

    private fun overLimit(capture: ActiveCapture) =
        otherSessionsBytes + capture.writer.bytesWritten > retention.maxTotalBytes

    /**
     * Ends [only] if given (and still active), otherwise whatever capture is active. Start and end
     * are serialised by [lifecycle]: they suspend while draining, and a start interleaved into an
     * end would have its sensors stopped by the end it overtook.
     */
    private suspend fun end(only: ActiveCapture? = null): Unit = lifecycle.withLock { endLocked(only) }

    private suspend fun endLocked(only: ActiveCapture?) {
        val capture = active ?: return
        if (only != null && capture !== only) return
        active = null
        stopProducers(capture)
        if (capture.failed) return
        capture.guarded("finish") {
            capture.writer.append(anchor(AnchorReason.END))
            capture.writer.finish {
                it.copy(complete = true, endedWallMs = clock.wallMs(), stats = capture.stats())
            }
        }
    }

    /** Stops frames and sensors, then lets the queues drain what they already hold. */
    private suspend fun stopProducers(capture: ActiveCapture) {
        capture.frameJob?.cancelAndJoin()
        runCatching { sensors.stop() }
        capture.encodeQueue.close()
        capture.sensorQueue.close()
        capture.anchorJob?.cancel()
        // The consumers end on their own once their closed queues are drained.
        capture.sensorWriterJob?.join()
        capture.frameWriterJob?.join()
    }

    private suspend fun fail(capture: ActiveCapture, reason: String) {
        if (capture.failed) return
        capture.failed = true
        if (active === capture) active = null
        log.warning(TAG, "Field capture failed: $reason")
        capture.frameJob?.cancel()
        runCatching { sensors.stop() }
        capture.encodeQueue.close()
        capture.sensorQueue.close()
        listOfNotNull(capture.sensorWriterJob, capture.frameWriterJob, capture.anchorJob).forEach { it.cancel() }
        runCatching {
            capture.writer.finish {
                it.copy(complete = false, failureReason = reason, endedWallMs = clock.wallMs(), stats = capture.stats())
            }
        }.onFailure { runCatching { capture.writer.close() } }
    }

    private fun writeSensor(capture: ActiveCapture, record: SensorRecord) {
        capture.writer.append(record)
        capture.sensorEvents.incrementAndGet()
    }

    private fun writeFrame(capture: ActiveCapture, pending: PendingFrame) {
        if (capture.writer.manifest.camera == null) {
            // The camera may have bound after the session started.
            runCatching { cameraFacts.currentSnapshot() }.getOrNull()?.toRecord()?.let { camera ->
                capture.writer.updateManifest { it.copy(camera = camera) }
            }
        }
        val image = pending.image
        val (encoding, file) = when (image) {
            is PendingImage.Nv21 -> FrameEncoding.JPEG to capture.writer.writeFrameImage(
                "%06d.jpg".format(pending.seq),
                encoder.encode(image.image, config.jpegQuality),
            )
            is PendingImage.Luma -> FrameEncoding.LUMA8 to capture.writer.writeFrameImage(
                "%06d.y".format(pending.seq),
                image.image.bytes,
            )
        }
        capture.writer.append(
            FrameRecord(
                seq = pending.seq,
                sensorTimestampNanos = pending.sensorTimestampNanos,
                receivedElapsedRealtimeNanos = pending.receivedElapsedRealtimeNanos,
                sourceWidth = pending.sourceWidth,
                sourceHeight = pending.sourceHeight,
                rotationDegrees = pending.rotationDegrees,
                encoding = encoding,
                file = file,
                width = image.width,
                height = image.height,
                sensorToBufferTransform = pending.sensorToBufferTransform,
                cropRect = pending.cropRect,
            ),
        )
        capture.encoded.incrementAndGet()
        capture.lastStoredFrameSeq = pending.seq
    }

    // ---- frame thread --------------------------------------------------------------------------

    private suspend fun collectFrames(capture: ActiveCapture) {
        // The burst takes every frame the source delivers; field evidence samples.
        val frames = if (capture.timingSync) frameSource.frames else frameSource.frames(config.frameIntervalMs)
        try {
            frames.collect { frame ->
                try {
                    capture.offered.incrementAndGet()
                    toPending(capture, frame)?.let { capture.encodeQueue.trySend(it) }
                } finally {
                    // Borrowed only for the copy above.
                    frameSource.releaseFrame(frame)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            scope.launch { fail(capture, "frame collection: ${e.message}") }
        }
    }

    private fun toPending(capture: ActiveCapture, frame: ImageFrame): PendingFrame? {
        val image = if (capture.timingSync) {
            LumaDownscaler.downscale(frame, config.timingSync.maxLongSide)?.let(PendingImage::Luma)
        } else {
            Nv21Downscaler.downscale(frame, config.maxLongSide)?.let(PendingImage::Nv21)
        } ?: return null
        val geometry = frame.sourceGeometry
        return PendingFrame(
            seq = frame.sequenceNumber,
            sensorTimestampNanos = frame.timestamp,
            receivedElapsedRealtimeNanos = frame.receivedElapsedRealtimeNanos,
            sourceWidth = frame.width,
            sourceHeight = frame.height,
            rotationDegrees = frame.rotationDegrees,
            sensorToBufferTransform = geometry?.sensorToBufferTransform,
            cropRect = geometry?.let { listOf(it.cropLeft, it.cropTop, it.cropRight, it.cropBottom) },
            image = image,
        )
    }

    private fun anchor(reason: AnchorReason) =
        ClockAnchorRecord(wallMs = clock.wallMs(), elapsedRealtimeNanos = clock.elapsedRealtimeNanos(), reason = reason)

    /** Capture's own copy of a frame: NV21 to be JPEG-encoded (field evidence) or raw luma (burst). */
    private sealed interface PendingImage {
        val width: Int
        val height: Int

        class Nv21(val image: Nv21Image) : PendingImage {
            override val width get() = image.width
            override val height get() = image.height
        }

        class Luma(val image: LumaImage) : PendingImage {
            override val width get() = image.width
            override val height get() = image.height
        }
    }

    private class PendingFrame(
        val seq: Long,
        val sensorTimestampNanos: Long,
        val receivedElapsedRealtimeNanos: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val rotationDegrees: Int,
        val sensorToBufferTransform: List<Float>?,
        val cropRect: List<Int>?,
        val image: PendingImage,
    )

    private inner class ActiveCapture(val writer: CaptureSessionWriter, val mode: String) {
        val sessionId: String = writer.directory.name
        val timingSync: Boolean = mode == CaptureModes.TIMING_SYNC
        val offered = AtomicLong()
        val encoded = AtomicLong()
        val droppedByEncoder = AtomicLong()
        val sensorEvents = AtomicLong()
        val sensorDropped = AtomicLong()
        val markers = AtomicLong()
        @Volatile var lastStoredFrameSeq: Long? = null
        var failed = false
        var frameJob: Job? = null
        var sensorWriterJob: Job? = null
        var frameWriterJob: Job? = null
        var anchorJob: Job? = null
        var burstJob: Job? = null

        val encodeQueue = Channel<PendingFrame>(
            capacity = if (timingSync) config.timingSync.queueCapacity else config.encodeQueueCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { droppedByEncoder.incrementAndGet() },
        )
        val sensorQueue = Channel<SensorRecord>(
            capacity = config.sensorQueueCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { sensorDropped.incrementAndGet() },
        )

        fun stats() = CaptureStats(
            framesOffered = offered.get(),
            framesEncoded = encoded.get(),
            framesDroppedByEncoder = droppedByEncoder.get(),
            sensorEvents = sensorEvents.get(),
            sensorEventsDropped = sensorDropped.get(),
            markers = markers.get(),
        )

        /** Runs [block] on the writer thread; any failure ends this capture as failed. */
        suspend fun guarded(what: String, block: () -> Unit) {
            if (failed) return
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(this, "$what: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "FieldCapture"

        /** `failureReason` of a capture ended because the capture directory reached its cap. */
        const val STORAGE_LIMIT_REACHED: String = "storage_limit_reached"

        /** `failureReason` of a capture that started while a ZIP export was running. */
        const val EXPORT_IN_PROGRESS: String = "export_in_progress"
    }
}

internal fun CameraCharacteristicsSnapshot.toRecord() = CaptureCameraRecord(
    cameraId = cameraId,
    capturedAtElapsedRealtimeNanos = capturedAtElapsedRealtimeNanos,
    sensorOrientationDegrees = sensorOrientationDegrees,
    timestampSource = when (timestampSource) {
        CameraTimestampSource.REALTIME -> "realtime"
        CameraTimestampSource.UNKNOWN -> "unknown"
        CameraTimestampSource.NOT_REPORTED -> "not_reported"
    },
    lensIntrinsicCalibration = lensIntrinsicCalibration,
    lensDistortion = lensDistortion,
    lensPoseRotation = lensPoseRotation,
    lensPoseTranslation = lensPoseTranslation,
    activeArray = activeArray?.toList(),
    preCorrectionActiveArray = preCorrectionActiveArray?.toList(),
    pixelArrayWidth = pixelArrayWidth,
    pixelArrayHeight = pixelArrayHeight,
    physicalSizeWidthMm = physicalSizeWidthMm,
    physicalSizeHeightMm = physicalSizeHeightMm,
    availableFocalLengthsMm = availableFocalLengthsMm,
)

private fun PixelRect.toList() = listOf(left, top, right, bottom)

/** A missed-alert press, fixed when it was observed; see [FieldCaptureController.observeMarker]. */
internal data class MarkerObservation(
    val sessionId: String,
    val wallMs: Long,
    val elapsedRealtimeNanos: Long,
    val lastStoredFrameSeq: Long?,
    val source: MarkerSource,
)
