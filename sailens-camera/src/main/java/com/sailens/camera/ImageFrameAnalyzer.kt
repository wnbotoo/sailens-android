package com.sailens.camera

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.frame.Yuv420FrameData
import com.sailens.core.frame.YuvPlaneData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

public class ImageFrameAnalyzer(
    private val frameConverter: ImageFrameConverter = ImageProxyToFrameConverter(),
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : ImageAnalysis.Analyzer, FrameSource, FrameSnapshotProvider {
    private var nextSequenceNumber = 0L
    private val emittedFrames = AtomicLong(0L)
    private val droppedFrames = AtomicLong(0L)
    private val skippedFramesWithoutDemand = AtomicLong(0L)
    private val snapshotLeases = AtomicInteger(0)
    private val latestFrame = LatestFrameHolder(elapsedRealtimeMs)

    private val _frames = MutableSharedFlow<ImageFrame>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val frames: SharedFlow<ImageFrame> = _frames.asSharedFlow()
    public val stats: ImageFrameAnalyzerStats
        get() = ImageFrameAnalyzerStats(
            emittedFrames = emittedFrames.get(),
            droppedFrames = droppedFrames.get(),
            skippedFramesWithoutDemand = skippedFramesWithoutDemand.get(),
            openSnapshotLeases = snapshotLeases.get(),
        )

    /**
     * Converting a frame copies every plane, so it only happens when somebody has said they want
     * one. The two kinds of demand are independent on purpose (architecture.md §6.1): Guidance
     * collecting [frames], and a snapshot lease. Either alone keeps conversion running, which is
     * what lets Describe work while Guidance is stopped.
     */
    override fun analyze(image: ImageProxy) {
        image.use { proxy ->
            val streamSubscribers = _frames.subscriptionCount.value
            if (streamSubscribers == 0 && snapshotLeases.get() == 0) {
                skippedFramesWithoutDemand.incrementAndGet()
                return
            }
            val frame = frameConverter.convert(
                image = proxy,
                sequenceNumber = nextSequenceNumber++,
            )
            latestFrame.record(frame)
            if (streamSubscribers == 0) {
                // Converted for a snapshot lease only. Nothing is collecting, so there is no
                // emission to count either way.
                return
            }
            if (_frames.tryEmit(frame)) {
                emittedFrames.incrementAndGet()
            } else {
                droppedFrames.incrementAndGet()
            }
        }
    }

    /**
     * The snapshot is the last frame this analyzer converted. A dropped frame still counts: it was
     * current, it just lost the race into the stream buffer.
     */
    override fun currentFrame(maxAgeMs: Long): ImageFrame? = latestFrame.currentFrame(maxAgeMs)

    override suspend fun awaitCurrentFrame(maxAgeMs: Long, timeoutMs: Long): ImageFrame? {
        if (timeoutMs < 0) return null
        return openSnapshotLease().use {
            withTimeoutOrNull(timeoutMs) { latestFrame.awaitFrame(maxAgeMs) }
        }
    }

    override fun openSnapshotLease(): FrameLease {
        snapshotLeases.incrementAndGet()
        return SnapshotLease()
    }

    private inner class SnapshotLease : FrameLease {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                snapshotLeases.decrementAndGet()
            }
        }
    }
}

public data class ImageFrameAnalyzerStats(
    val emittedFrames: Long,
    val droppedFrames: Long,
    /** Frames the camera delivered while nothing wanted one, so they were never converted. */
    val skippedFramesWithoutDemand: Long,
    val openSnapshotLeases: Int,
)

public interface ImageFrameConverter {
    public fun convert(
        image: ImageProxy,
        sequenceNumber: Long,
    ): ImageFrame
}

public class ImageProxyToFrameConverter : ImageFrameConverter {
    override fun convert(
        image: ImageProxy,
        sequenceNumber: Long,
    ): ImageFrame {
        return when (image.format) {
            ImageFormat.YUV_420_888 -> createYuvFrame(image, sequenceNumber)
            PixelFormat.RGBA_8888 -> createRgbaFrame(image, sequenceNumber)
            ImageFormat.FLEX_RGBA_8888 -> createRgbaFrame(image, sequenceNumber)
            else -> error("Unsupported image format ${image.format}; expected YUV_420_888 or RGBA_8888")
        }
    }

    private fun createYuvFrame(
        image: ImageProxy,
        sequenceNumber: Long,
    ): ImageFrame {
        return ImageFrame(
            width = image.width,
            height = image.height,
            pixelBytes = ByteArray(0),
            pixelFormat = ImagePixelFormat.YUV_420_888,
            rotationDegrees = image.imageInfo.rotationDegrees,
            timestamp = image.imageInfo.timestamp,
            sequenceNumber = sequenceNumber,
            yuvData = Yuv420FrameData(
                y = copyPlane(image.planes[0]),
                u = copyPlane(image.planes[1]),
                v = copyPlane(image.planes[2]),
            ),
        )
    }

    private fun createRgbaFrame(
        image: ImageProxy,
        sequenceNumber: Long,
    ): ImageFrame {
        return ImageFrame(
            width = image.width,
            height = image.height,
            pixelBytes = copyRgba(image),
            pixelFormat = ImagePixelFormat.RGBA_8888,
            rotationDegrees = image.imageInfo.rotationDegrees,
            timestamp = image.imageInfo.timestamp,
            sequenceNumber = sequenceNumber,
        )
    }

    private fun copyRgba(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val rgba = ByteArray(width * height * ImagePixelFormat.RGBA_8888.bytesPerPixel)
        val plane = image.planes.firstOrNull()
            ?: error("RGBA image has no planes")
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val outputRowBytes = width * ImagePixelFormat.RGBA_8888.bytesPerPixel

        if (pixelStride == ImagePixelFormat.RGBA_8888.bytesPerPixel) {
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(rgba, row * outputRowBytes, outputRowBytes)
            }
            return rgba
        }

        var outputIndex = 0
        for (y in 0 until height) {
            val rowOffset = y * rowStride
            for (x in 0 until width) {
                val pixelOffset = rowOffset + x * pixelStride
                rgba[outputIndex++] = buffer.get(pixelOffset)
                rgba[outputIndex++] = buffer.get(pixelOffset + 1)
                rgba[outputIndex++] = buffer.get(pixelOffset + 2)
                rgba[outputIndex++] = buffer.get(pixelOffset + 3)
            }
        }
        return rgba
    }

    private fun copyPlane(plane: ImageProxy.PlaneProxy): YuvPlaneData {
        val buffer = plane.buffer.duplicate()
        buffer.position(0)
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return YuvPlaneData(
            bytes = bytes,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
        )
    }
}
