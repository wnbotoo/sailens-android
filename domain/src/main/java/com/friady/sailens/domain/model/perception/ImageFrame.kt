package com.friady.sailens.domain.model.perception

import android.graphics.Bitmap

/**
 * 图像数据（平台无关）
 */
data class ImageFrame(
    val width: Int,
    val height: Int,
//    val nv21Pixels: ByteArray,
    val bitmap: Bitmap,
    val timestamp: Long,
    val rotationDegrees: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageFrame) return false
        return width == other.width && height == other.height && bitmap == other.bitmap && rotationDegrees == other.rotationDegrees
    }

    override fun hashCode(): Int = 31 * width + height
}
