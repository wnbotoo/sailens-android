package com.sailens.vision.depth

import com.sailens.core.frame.ImageFrame
import kotlin.math.abs

/**
 * How much fine detail a frame's luma has: the mean absolute difference between a pixel and its right
 * and lower neighbours, over a [GRID]×[GRID] grid of sample points. Near zero for a covered or badly
 * defocused lens, tens for a sharp outdoor scene.
 *
 * Recorded with every depth run so a threshold can be calibrated on this measure later; nothing
 * decides on it today. It is not the Laplacian sharpness of the offline experiments, and their numbers
 * do not convert to it.
 */
object ImageDetail {
    const val GRID: Int = 64

    /** Null when the frame has no YUV planes or is too small to sample. */
    fun measure(frame: ImageFrame): Float? {
        val plane = frame.yuvData?.y ?: return null
        val width = frame.width
        val height = frame.height
        if (width < 2 || height < 2) return null
        val bytes = plane.bytes
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        fun luma(x: Int, y: Int): Int = bytes[y * rowStride + x * pixelStride].toInt() and 0xFF

        var sum = 0L
        var count = 0
        for (gy in 0 until GRID) {
            val y = ((gy + 0.5f) * (height - 1) / GRID).toInt()
            for (gx in 0 until GRID) {
                val x = ((gx + 0.5f) * (width - 1) / GRID).toInt()
                val centre = luma(x, y)
                sum += abs(luma(x + 1, y) - centre) + abs(luma(x, y + 1) - centre)
                count += 2
            }
        }
        return sum.toFloat() / count
    }
}
