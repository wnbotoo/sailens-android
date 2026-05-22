package com.friady.sailens.domain.model.perception

/**
 * 语义分割模型输出
 */
data class SegmentationOutput(
    val mask: SegmentationMask,
    val preprocessTimeMs: Long,
    val inferenceTimeMs: Long,
    val postprocessTimeMs: Long,
    val modelTimeMs: Long = inferenceTimeMs,
    val outputReadTimeMs: Long = 0,
)

/**
 * 封装分割结果的原始数据和维度
 * 独立出来方便在 UI 层或其他逻辑中单独传递
 */
data class SegmentationMask(
    val width: Int,
    val height: Int,
    val classMap: IntArray
) {
    /**
     * 获取指定坐标 (x, y) 的类别 ID
     */
    fun getClassId(x: Int, y: Int): Int {
        if (x !in 0..<width || y < 0 || y >= height) {
            throw IndexOutOfBoundsException("Coordinate ($x, $y) out of bounds for ${width}x${height}")
        }
        return classMap[y * width + x]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SegmentationMask

        if (width != other.width) return false
        if (height != other.height) return false
        if (!classMap.contentEquals(other.classMap)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + classMap.contentHashCode()
        return result
    }
}
