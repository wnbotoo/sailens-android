package com.friady.sailens.domain.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

object ImageUtil {
    fun nv21ScaleAndNormalizeToBuffer(
        nv21: ByteArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        mean: Float,
        std: Float
    ): ByteBuffer {
        val output = ByteBuffer
            .allocateDirect(dstW * dstH * 3 * 4)
            .order(ByteOrder.nativeOrder())

        val yRatio = srcH.toFloat() / dstH
        val xRatio = srcW.toFloat() / dstW
        val frameSize = srcW * srcH

        for (j in 0 until dstH) {
            val srcY = (j * yRatio).toInt()
            val uvRow = frameSize + (srcY shr 1) * srcW
            for (i in 0 until dstW) {
                val srcX = (i * xRatio).toInt()
                val yIndex = srcY * srcW + srcX
                val uvIndex = uvRow + (srcX and 0.inv()) // 偶数取V，奇数取U

                val y = (nv21[yIndex].toInt() and 0xFF) - 16
                val v = (nv21[uvIndex].toInt() and 0xFF) - 128
                val u = (nv21[uvIndex + 1].toInt() and 0xFF) - 128

                val yF = if (y < 0) 0f else y.toFloat()
                val r = clamp(1.164f * yF + 1.596f * v)
                val g = clamp(1.164f * yF - 0.813f * v - 0.391f * u)
                val b = clamp(1.164f * yF + 2.018f * u)

                output.putFloat((r - mean) / std)
                output.putFloat((g - mean) / std)
                output.putFloat((b - mean) / std)
            }
        }
        output.rewind()
        return output
    }

    private fun clamp(v: Float) = max(0f, min(255f, v))
}