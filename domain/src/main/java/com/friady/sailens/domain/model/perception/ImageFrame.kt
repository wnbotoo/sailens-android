package com.friady.sailens.domain.model.perception

/**
 * 图像数据（平台无关）。
 *
 * 图像帧在 domain 层保持平台无关；CameraX 的 ImageProxy 会在 camera 模块
 * 转换成这里的紧凑数据结构。
 */
enum class ImagePixelFormat(
    val bytesPerPixel: Int,
) {
    RGBA_8888(bytesPerPixel = 4),
    YUV_420_888(bytesPerPixel = 0),
}

data class YuvPlaneData(
    val bytes: ByteArray,
    val rowStride: Int,
    val pixelStride: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is YuvPlaneData) return false
        return rowStride == other.rowStride &&
            pixelStride == other.pixelStride &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + rowStride
        result = 31 * result + pixelStride
        return result
    }
}

data class Yuv420FrameData(
    val y: YuvPlaneData,
    val u: YuvPlaneData,
    val v: YuvPlaneData,
)

data class ImageFrame(
    val width: Int,
    val height: Int,
    val pixelBytes: ByteArray,
    val pixelFormat: ImagePixelFormat,
    val timestamp: Long,
    val rotationDegrees: Int,
    val sequenceNumber: Long,
    val yuvData: Yuv420FrameData? = null,
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
            pixelBytes.contentEquals(other.pixelBytes) &&
            yuvData == other.yuvData
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixelBytes.contentHashCode()
        result = 31 * result + pixelFormat.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + rotationDegrees
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + (yuvData?.hashCode() ?: 0)
        return result
    }
}
