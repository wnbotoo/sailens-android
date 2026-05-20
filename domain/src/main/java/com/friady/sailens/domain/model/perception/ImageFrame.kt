package com.friady.sailens.domain.model.perception

/**
 * 图像数据（平台无关）
 */
data class ImageFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val timestamp: Long,
    val rotationDegrees: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageFrame) return false
        return width == other.width &&
            height == other.height &&
            rotationDegrees == other.rotationDegrees &&
            pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        result = 31 * result + rotationDegrees
        return result
    }
}
