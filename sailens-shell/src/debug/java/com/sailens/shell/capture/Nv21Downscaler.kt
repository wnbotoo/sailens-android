package com.sailens.shell.capture

import com.sailens.core.frame.ImageFrame

/** A small NV21 image (full-res Y, then interleaved V/U at half resolution), not rotated. */
internal class Nv21Image(val width: Int, val height: Int, val bytes: ByteArray)

/**
 * Copies a frame into a downscaled NV21 image, nearest-neighbour. Field evidence only needs to be
 * recognisable for labelling and geometry; exact pixels are M0b's job.
 *
 * Works on any YUV_420_888 layout (padded rows, pixel stride 1 or 2). Output dimensions keep the
 * aspect ratio, fit [maxLongSide], and are even, as NV21 requires.
 */
internal object Nv21Downscaler {

    fun targetSize(width: Int, height: Int, maxLongSide: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxLongSide >= 2)
        val scale = minOf(1.0, maxLongSide.toDouble() / maxOf(width, height))
        val w = even((width * scale).toInt())
        val h = even((height * scale).toInt())
        return w to h
    }

    /** Returns null when the frame carries no YUV planes (e.g. an RGBA frame). */
    fun downscale(frame: ImageFrame, maxLongSide: Int): Nv21Image? {
        val yuv = frame.yuvData ?: return null
        val (w, h) = targetSize(frame.width, frame.height, maxLongSide)
        val out = ByteArray(w * h * 3 / 2)

        val y = yuv.y
        for (row in 0 until h) {
            val srcRow = row * frame.height / h
            val base = srcRow * y.rowStride
            val dst = row * w
            for (col in 0 until w) {
                val srcCol = col * frame.width / w
                out[dst + col] = y.bytes[base + srcCol * y.pixelStride]
            }
        }

        val u = yuv.u
        val v = yuv.v
        val chromaW = w / 2
        val chromaH = h / 2
        val srcChromaW = (frame.width + 1) / 2
        val srcChromaH = (frame.height + 1) / 2
        var dst = w * h
        for (row in 0 until chromaH) {
            val srcRow = row * srcChromaH / chromaH
            for (col in 0 until chromaW) {
                val srcCol = col * srcChromaW / chromaW
                out[dst++] = v.bytes[srcRow * v.rowStride + srcCol * v.pixelStride]
                out[dst++] = u.bytes[srcRow * u.rowStride + srcCol * u.pixelStride]
            }
        }
        return Nv21Image(w, h, out)
    }

    private fun even(value: Int): Int = maxOf(2, value - value % 2)
}
