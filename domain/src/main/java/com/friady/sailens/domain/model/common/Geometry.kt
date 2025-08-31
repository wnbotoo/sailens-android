package com.friady.sailens.domain.model.common

/**
 * 归一化矩形
 */
data class NormalizedRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val centerX: Float get() = x + width / 2
    val centerY: Float get() = y + height / 2
    val maxX: Float get() = x + width
    val maxY: Float get() = y + height
    val area: Float get() = width * height

    fun iou(other: NormalizedRect): Float {
        val intersectX = maxOf(x, other.x)
        val intersectY = maxOf(y, other.y)
        val intersectMaxX = minOf(maxX, other.maxX)
        val intersectMaxY = minOf(maxY, other.maxY)

        if (intersectMaxX <= intersectX || intersectMaxY <= intersectY) {
            return 0f
        }

        val intersectArea = (intersectMaxX - intersectX) * (intersectMaxY - intersectY)
        val unionArea = area + other.area - intersectArea

        return if (unionArea > 0) intersectArea / unionArea else 0f
    }

    companion object {
        fun fromPixels(
            px: Int, py: Int, pw: Int, ph: Int,
            imageWidth: Int, imageHeight: Int,
        ): NormalizedRect {
            return NormalizedRect(
                x = px.toFloat() / imageWidth,
                y = py.toFloat() / imageHeight,
                width = pw.toFloat() / imageWidth,
                height = ph.toFloat() / imageHeight
            )
        }
    }
}
