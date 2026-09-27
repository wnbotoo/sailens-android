package com.sailens.shell.capture

import com.sailens.core.frame.ImageFrame

/** Raw 8-bit luma, row-major, `width × height` bytes, not rotated. */
internal class LumaImage(val width: Int, val height: Int, val bytes: ByteArray)

/**
 * Copies a frame's Y plane into a small luma image by area averaging, for the timing-sync burst.
 *
 * Averaging rather than nearest-neighbour: the burst is analysed for image motion, and point
 * sampling aliases fine texture into motion that is not there. Works on any YUV_420_888 layout
 * (padded rows, pixel stride 1 or 2). Output keeps the aspect ratio and fits [maxLongSide].
 */
internal object LumaDownscaler {

    fun targetSize(width: Int, height: Int, maxLongSide: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxLongSide >= 1)
        val scale = minOf(1.0, maxLongSide.toDouble() / maxOf(width, height))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    /** Returns null when the frame carries no YUV planes (e.g. an RGBA frame). */
    fun downscale(frame: ImageFrame, maxLongSide: Int): LumaImage? {
        val y = frame.yuvData?.y ?: return null
        val (w, h) = targetSize(frame.width, frame.height, maxLongSide)
        val out = ByteArray(w * h)
        // Source cell edges; every output pixel averages a non-empty cell because w <= width.
        val xEdges = IntArray(w + 1) { it * frame.width / w }
        val src = y.bytes
        for (row in 0 until h) {
            val y0 = row * frame.height / h
            val y1 = maxOf(y0 + 1, (row + 1) * frame.height / h)
            for (col in 0 until w) {
                val x0 = xEdges[col]
                val x1 = maxOf(x0 + 1, xEdges[col + 1])
                var sum = 0
                for (sy in y0 until y1) {
                    var index = sy * y.rowStride + x0 * y.pixelStride
                    for (sx in x0 until x1) {
                        sum += src[index].toInt() and 0xFF
                        index += y.pixelStride
                    }
                }
                out[row * w + col] = (sum / ((y1 - y0) * (x1 - x0))).toByte()
            }
        }
        return LumaImage(w, h, out)
    }
}
