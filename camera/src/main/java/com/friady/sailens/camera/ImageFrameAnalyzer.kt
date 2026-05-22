package com.friady.sailens.camera

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.ImagePixelFormat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer

class ImageFrameAnalyzer(
    private val frameConverter: ImageFrameConverter = ImageProxyToRgbaFrameConverter(),
) : ImageAnalysis.Analyzer, ImageFrameProvider {
    private var nextSequenceNumber = 0L

    private val _frames = MutableSharedFlow<ImageFrame>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val frames: SharedFlow<ImageFrame> = _frames.asSharedFlow()

    override fun analyze(image: ImageProxy) {
        image.use { proxy ->
            val frame = frameConverter.convert(
                image = proxy,
                sequenceNumber = nextSequenceNumber++,
            )
            _frames.tryEmit(frame)
        }
    }
}

interface ImageFrameConverter {
    fun convert(
        image: ImageProxy,
        sequenceNumber: Long,
    ): ImageFrame
}

class ImageProxyToRgbaFrameConverter : ImageFrameConverter {
    override fun convert(
        image: ImageProxy,
        sequenceNumber: Long,
    ): ImageFrame {
        val rgba = when (image.format) {
            PixelFormat.RGBA_8888 -> copyRgba(image)
            ImageFormat.FLEX_RGBA_8888 -> copyRgba(image)
            ImageFormat.YUV_420_888 -> convertYuvToRgba(image)
            else -> error("Unsupported image format ${image.format}; expected RGBA_8888 or YUV_420_888")
        }

        return ImageFrame(
            width = image.width,
            height = image.height,
            pixelBytes = rgba,
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

    private fun convertYuvToRgba(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val rgba = ByteArray(width * height * ImagePixelFormat.RGBA_8888.bytesPerPixel)
        yuv420ToRgba(image.planes, width, height, rgba)
        return rgba
    }

    private fun yuv420ToRgba(
        planes: Array<ImageProxy.PlaneProxy>,
        width: Int,
        height: Int,
        output: ByteArray,
    ) {
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        var outIndex = 0
        for (y in 0 until height) {
            val yRowOffset = y * yPlane.rowStride
            val uRowOffset = (y / 2) * uPlane.rowStride
            val vRowOffset = (y / 2) * vPlane.rowStride

            for (x in 0 until width) {
                val yValue = yBuffer.unsigned(yRowOffset + x * yPlane.pixelStride)
                val uvColumnOffset = (x / 2) * uPlane.pixelStride
                val uValue = uBuffer.unsigned(uRowOffset + uvColumnOffset)
                val vValue = vBuffer.unsigned(
                    vRowOffset + (x / 2) * vPlane.pixelStride
                )

                val c = (yValue - 16).coerceAtLeast(0)
                val d = uValue - 128
                val e = vValue - 128

                output[outIndex++] = clampToByte((298 * c + 409 * e + 128) shr 8)
                output[outIndex++] = clampToByte((298 * c - 100 * d - 208 * e + 128) shr 8)
                output[outIndex++] = clampToByte((298 * c + 516 * d + 128) shr 8)
                output[outIndex++] = FULL_ALPHA
            }
        }
    }

    private fun ByteBuffer.unsigned(index: Int): Int {
        val limit = limit()
        if (index < 0 || index >= limit) {
            throw IllegalArgumentException(
                "Plane buffer index $index out of bounds [0, $limit)"
            )
        }
        return get(index).toInt() and 0xFF
    }

    private fun clampToByte(value: Int): Byte {
        return value.coerceIn(0, 255).toByte()
    }

    private companion object {
        private val FULL_ALPHA: Byte = 0xFF.toByte()
    }
}
