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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
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

internal data class FieldCaptureConfig(
    val frameIntervalMs: Long = 200,
    val maxLongSide: Int = 640,
    val jpegQuality: Int = 80,
    /** Frames waiting for the encoder. Small on purpose: a late frame is dropped, not queued. */
    val encodeQueueCapacity: Int = 1,
    val sensorQueueCapacity: Int = 4096,
    val anchorIntervalMs: Long = 30_000,
)

/**
 * Field evidence capture for one Guidance session at a time (M0a, implementation §4).
 *
 * Rules this class exists to keep:
 * - **Capture never fails or blocks Guidance.** The session hooks only read a flag and hand work to
 *   the capture's own thread; every failure inside capture ends as a failed, incomplete capture on
 *   disk and a log line, never as an exception to the caller.
 * - **The mode is read once**, when a session starts.
 * - **Frames are borrowed briefly.** Each frame is downscaled into capture's own buffer and
 *   released to the frame source immediately, whatever happens next.
 * - **Nothing piles up.** At most [FieldCaptureConfig.encodeQueueCapacity] frames wait for the
 *   encoder; a newer frame replaces a waiting one and the drop is counted.
 * - **One writer thread.** All file writes happen on [writerDispatcher], so the writer needs no locks.
 */
internal class FieldCaptureController(
    private val root: File,
    private val isEnabled: () -> Boolean,
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
) {
    private val scope = CoroutineScope(
        SupervisorJob() + writerDispatcher + CoroutineExceptionHandler { _, throwable ->
            log.warning(TAG, "Field capture task failed", throwable = throwable)
        },
    )

    /** Confined to [writerDispatcher]. */
    private var active: ActiveCapture? = null

    /** Bytes held by the sessions other than the active one, as of the last retention pass. */
    private var otherSessionsBytes = 0L

    init {
        // A maintenance opportunity at start-up, whether or not capture is switched on, so expired
        // captures do not stay on the device just because nobody records any more.
        scope.launch { maintain() }
    }

    /** Runs retention now (e.g. when the capture list opens). Never throws. */
    fun runMaintenance(): Job = scope.launch { maintain() }

    /** Called when a trace session starts. Never throws, never blocks. */
    fun onSessionStarted(sessionId: String, targetHardwareProfile: String?): Job? {
        val enabled = runCatching(isEnabled).getOrDefault(false)
        if (!enabled) return null
        return scope.launch { begin(sessionId, targetHardwareProfile) }
    }

    /** Called when the trace session finishes. Never throws, never blocks. */
    fun onSessionFinished(): Job = scope.launch { end() }

    /**
     * Records a "missed alert" marker in the active capture. Returns whether a capture was active to
     * take it, so the caller can confirm the press only when it was recorded.
     */
    suspend fun recordMarker(source: MarkerSource): Boolean {
        val result = CompletableDeferred<Boolean>()
        scope.launch {
            val capture = active
            if (capture == null) {
                result.complete(false)
                return@launch
            }
            capture.guarded("marker") {
                capture.writer.append(
                    MarkerRecord(
                        kind = MarkerKind.MISSED_ALERT,
                        wallMs = clock.wallMs(),
                        elapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
                        lastStoredFrameSeq = capture.lastStoredFrameSeq,
                        source = source,
                    ),
                )
                capture.writer.flush()
                capture.markers.incrementAndGet()
            }
            result.complete(!capture.failed)
        }.invokeOnCompletion { if (!result.isCompleted) result.complete(false) }
        return result.await()
    }

    // ---- writer thread -------------------------------------------------------------------------

    private suspend fun begin(sessionId: String, targetHardwareProfile: String?) {
        // A session that never finished (the app kept running but the hook was missed) is closed
        // as incomplete rather than silently mixed with the new one.
        active?.let { fail(it, "superseded by session $sessionId") }

        maintain(activeSessionId = sessionId, activeBytes = 0)

        val manifest = CaptureManifest(
            sessionId = sessionId,
            captureMode = CaptureModes.FIELD_EVIDENCE,
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
        val capture = ActiveCapture(writer)
        active = capture

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
    }

    /**
     * Retention pass. The active session is never deleted, only counted; its size comes from the
     * writer so that no directory walk is needed per frame.
     */
    private fun maintain(
        activeSessionId: String? = active?.writer?.directory?.name,
        activeBytes: Long = active?.writer?.bytesWritten ?: 0,
    ) {
        runCatching { retention.prune(clock.wallMs(), activeSessionId, activeBytes) }
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

    private suspend fun end() {
        val capture = active ?: return
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
        val jpeg = encoder.encode(pending.image, config.jpegQuality)
        val file = capture.writer.writeFrameImage("%06d.jpg".format(pending.seq), jpeg)
        capture.writer.append(
            FrameRecord(
                seq = pending.seq,
                sensorTimestampNanos = pending.sensorTimestampNanos,
                receivedElapsedRealtimeNanos = pending.receivedElapsedRealtimeNanos,
                sourceWidth = pending.sourceWidth,
                sourceHeight = pending.sourceHeight,
                rotationDegrees = pending.rotationDegrees,
                encoding = FrameEncoding.JPEG,
                file = file,
                width = pending.image.width,
                height = pending.image.height,
                sensorToBufferTransform = pending.sensorToBufferTransform,
                cropRect = pending.cropRect,
            ),
        )
        capture.encoded.incrementAndGet()
        capture.lastStoredFrameSeq = pending.seq
    }

    // ---- frame thread --------------------------------------------------------------------------

    private suspend fun collectFrames(capture: ActiveCapture) {
        try {
            frameSource.frames(config.frameIntervalMs).collect { frame ->
                try {
                    capture.offered.incrementAndGet()
                    toPending(frame)?.let { capture.encodeQueue.trySend(it) }
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

    private fun toPending(frame: ImageFrame): PendingFrame? {
        val image = Nv21Downscaler.downscale(frame, config.maxLongSide) ?: return null
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

    private class PendingFrame(
        val seq: Long,
        val sensorTimestampNanos: Long,
        val receivedElapsedRealtimeNanos: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val rotationDegrees: Int,
        val sensorToBufferTransform: List<Float>?,
        val cropRect: List<Int>?,
        val image: Nv21Image,
    )

    private inner class ActiveCapture(val writer: CaptureSessionWriter) {
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

        val encodeQueue = Channel<PendingFrame>(
            capacity = config.encodeQueueCapacity,
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
