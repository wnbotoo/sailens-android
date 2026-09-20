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
import java.util.concurrent.atomic.AtomicLong

class ImageFrameAnalyzer(
    private val frameConverter: ImageFrameConverter = ImageProxyToFrameConverter(),
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : ImageAnalysis.Analyzer, FrameSource, FrameSnapshotProvider {
    private var nextSequenceNumber = 0L
    private val emittedFrames = AtomicLong(0L)
    private val droppedFrames = AtomicLong(0L)
    private val skippedFramesWithoutSubscribers = AtomicLong(0L)
    private val latestFrame = LatestFrameHolder(elapsedRealtimeMs)

    private val _frames = MutableSharedFlow<ImageFrame>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val frames: SharedFlow<ImageFrame> = _frames.asSharedFlow()
    val stats: ImageFrameAnalyzerStats
        get() = ImageFrameAnalyzerStats(
            emittedFrames = emittedFrames.get(),
            droppedFrames = droppedFrames.get(),
            skippedFramesWithoutSubscribers = skippedFramesWithoutSubscribers.get(),
        )

    override fun analyze(image: ImageProxy) {
        image.use { proxy ->
            if (_frames.subscriptionCount.value == 0) {
                skippedFramesWithoutSubscribers.incrementAndGet()
                return
            }
            val frame = frameConverter.convert(
                image = proxy,
                sequenceNumber = nextSequenceNumber++,
            )
            latestFrame.record(frame)
            if (_frames.tryEmit(frame)) {
                emittedFrames.incrementAndGet()
            } else {
                droppedFrames.incrementAndGet()
            }
        }
    }

    /**
     * The snapshot is the last frame this analyzer converted, which only happens while something
     * collects [frames] -- see the gap noted on [FrameSnapshotProvider]. A dropped frame still
     * counts: it was current, it just lost the race into the buffer.
     */
    override fun currentFrame(maxAgeMs: Long): ImageFrame? = latestFrame.currentFrame(maxAgeMs)
}

data class ImageFrameAnalyzerStats(
    val emittedFrames: Long,
    val droppedFrames: Long,
    val skippedFramesWithoutSubscribers: Long,
)

interface ImageFrameConverter {
    fun convert(
        image: ImageProxy,
        sequenceNumber: Long,
    ): ImageFrame
}

class ImageProxyToFrameConverter : ImageFrameConverter {
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
