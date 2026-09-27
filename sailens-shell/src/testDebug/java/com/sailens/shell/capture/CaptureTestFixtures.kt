package com.sailens.shell.capture

import com.sailens.camera.FrameSource
import com.sailens.core.frame.FrameSourceGeometry
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.frame.Yuv420FrameData
import com.sailens.core.frame.YuvPlaneData
import com.sailens.core.log.LogService
import com.sailens.guidance.trace.capture.CaptureSensor
import com.sailens.guidance.trace.capture.SensorRecord
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** A frame source a test pushes frames into; it remembers which frames were handed back. */
internal class FakeFrameSource : FrameSource {
    private val channel = Channel<ImageFrame>(Channel.UNLIMITED)
    val released: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    override val frames: Flow<ImageFrame> get() = channel.receiveAsFlow()
    override fun frames(minIntervalMs: Long): Flow<ImageFrame> = channel.receiveAsFlow()
    override fun releaseFrame(frame: ImageFrame) {
        released += frame.sequenceNumber
    }

    fun emit(frame: ImageFrame) {
        channel.trySend(frame)
    }
}

internal class FakeSensors : CaptureSensorSource {
    @Volatile var onRecord: ((SensorRecord) -> Unit)? = null
    @Volatile var stopped = 0

    override fun start(onRecord: (SensorRecord) -> Unit): List<String> {
        this.onRecord = onRecord
        return listOf("gravity", "gyroscope")
    }

    override fun stop() {
        onRecord = null
        stopped++
    }

    fun emit(timestampNanos: Long) {
        onRecord?.invoke(SensorRecord(CaptureSensor.GRAVITY, timestampNanos, 3, listOf(0f, 9.8f, 0f)))
    }
}

internal class FakeClock : CaptureClock {
    private val wall = AtomicLong(1_700_000_000_000)
    private val elapsed = AtomicLong(5_000_000_000)
    override fun wallMs(): Long = wall.get()
    override fun elapsedRealtimeNanos(): Long = elapsed.get()
    fun advanceMs(ms: Long) {
        wall.addAndGet(ms)
        elapsed.addAndGet(ms * 1_000_000)
    }
}

/**
 * Collects warnings, so a test can wait for a capture failure without reading files the writer may
 * be replacing at that moment (Windows refuses to rename over a file another thread has open).
 */
internal class RecordingLog : LogService {
    val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())
    override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
    override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
    override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) {
        warnings += message
    }
    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}

internal val TEST_DEVICE = CaptureDeviceInfo("1.0", 1, null, "Acme", "Phone", 35)

/**
 * A YUV_420_888 frame with padded rows and interleaved chroma (pixel stride 2), the layout a real
 * camera hands over. Luma at (x, y) is `x + 10 * y`; U = 100 + x/2, V = 200 + y/2 at chroma resolution.
 */
internal fun yuvFrame(seq: Long, width: Int = 8, height: Int = 6, rowPadding: Int = 4): ImageFrame {
    val yStride = width + rowPadding
    val y = ByteArray(yStride * height) { i -> ((i % yStride) + 10 * (i / yStride)).toByte() }
    val chromaW = width / 2
    val chromaH = height / 2
    val cStride = width + rowPadding
    val u = ByteArray(cStride * chromaH)
    val v = ByteArray(cStride * chromaH)
    for (row in 0 until chromaH) for (col in 0 until chromaW) {
        u[row * cStride + col * 2] = (100 + col).toByte()
        v[row * cStride + col * 2] = (200 + row).toByte()
    }
    return ImageFrame(
        width = width,
        height = height,
        pixelBytes = ByteArray(0),
        pixelFormat = ImagePixelFormat.YUV_420_888,
        timestamp = 1_000_000L * seq,
        rotationDegrees = 90,
        sequenceNumber = seq,
        yuvData = Yuv420FrameData(
            y = YuvPlaneData(y, yStride, 1),
            u = YuvPlaneData(u, cStride, 2),
            v = YuvPlaneData(v, cStride, 2),
        ),
        receivedElapsedRealtimeNanos = 2_000_000L * seq,
        sourceGeometry = FrameSourceGeometry(
            sensorToBufferTransform = listOf(0.25f, 0f, 0f, 0f, 0.25f, 0f, 0f, 0f, 1f),
            cropLeft = 0, cropTop = 0, cropRight = width, cropBottom = height,
        ),
    )
}

internal fun awaitTrue(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) error("Timed out waiting for $what")
        Thread.sleep(2)
    }
}
