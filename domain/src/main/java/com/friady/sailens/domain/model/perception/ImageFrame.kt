package com.friady.sailens.domain.model.perception

/**
 * 图像数据（平台无关）。
 *
 * 当前主链路使用 RGBA_8888，后续如接入 GPU / NNAPI 友好的输入路径，可以在不触碰
 * CameraX 类型的前提下扩展新的像素格式。
 */
enum class ImagePixelFormat(
    val bytesPerPixel: Int,
) {
    RGBA_8888(bytesPerPixel = 4),
}

data class ImageFrame(
    val width: Int,
    val height: Int,
    val pixelBytes: ByteArray,
    val pixelFormat: ImagePixelFormat,
    val timestamp: Long,
    val rotationDegrees: Int,
    val sequenceNumber: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageFrame) return false
        return width == other.width &&
            height == other.height &&
            pixelFormat == other.pixelFormat &&
            timestamp == other.timestamp &&
            rotationDegrees == other.rotationDegrees &&
            sequenceNumber == other.sequenceNumber &&
            pixelBytes.contentEquals(other.pixelBytes)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixelBytes.contentHashCode()
        result = 31 * result + pixelFormat.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + rotationDegrees
        result = 31 * result + sequenceNumber.hashCode()
        return result
    }
}
