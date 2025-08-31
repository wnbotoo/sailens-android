package com.friady.sailens.domain.util

/**
 * NV21 数据预处理器：
 * - 支持缩放到目标尺寸并居中填充
 * - 支持每通道独立的 mean/std 归一化
 * - 默认提供 ImageNet 风格的快捷构造函数
 */
class NV21Preprocessor(
    private val dstW: Int, // 模型输入宽度
    private val dstH: Int, // 模型输入高度
    private val mean: Triple<Float, Float, Float>, // 每通道归一化均值 (R,G,B)
    private val std: Triple<Float, Float, Float>,  // 每通道归一化标准差 (R,G,B)
    private val padColor: Triple<Float, Float, Float> = Triple(0f, 0f, 0f), // 填充颜色（RGB）
) {

    // 预先计算每通道的 1/std，避免循环中重复除法
    private val invStdR = 1f / std.first
    private val invStdG = 1f / std.second
    private val invStdB = 1f / std.third

    /**
     * 将 NV21 数据转换为模型输入格式的 FloatArray，保持比例缩放并居中填充
     *
     * @param nv21 原始 NV21 数据
     * @param srcW 原始图像宽度
     * @param srcH 原始图像高度
     * @param outputArray 预分配的输出数组（大小为 dstW * dstH * 3）
     */
    fun convertNV21ToModelInput(
        nv21: ByteArray,
        srcW: Int,
        srcH: Int,
        outputArray: FloatArray,
    ) {
        require(nv21.size >= srcW * srcH * 3 / 2) {
            "NV21 buffer too small: expected ${srcW * srcH * 3 / 2}, got ${nv21.size}"
        }
        require(outputArray.size == dstW * dstH * 3) {
            "Output array size mismatch"
        }

        val frameSize = srcW * srcH
        val srcRatio = srcW.toFloat() / srcH
        val dstRatio = dstW.toFloat() / dstH

        // 根据比例决定缩放后的尺寸（保持原始比例）
        val scaleW: Int
        val scaleH: Int
        if (srcRatio > dstRatio) {
            scaleW = dstW
            scaleH = (dstW / srcRatio).toInt()
        } else {
            scaleH = dstH
            scaleW = (dstH * srcRatio).toInt()
        }

        // 计算从目标尺寸到原始尺寸的映射比例
        val xRatio = srcW.toFloat() / scaleW
        val yRatio = srcH.toFloat() / scaleH

        // 计算居中填充的偏移量
        val padLeft = (dstW - scaleW) / 2
        val padTop = (dstH - scaleH) / 2

        var outIdx = 0
        for (j in 0 until dstH) {
            for (i in 0 until dstW) {
                // 判断当前像素是否在有效图像区域内
                if (j in padTop until (padTop + scaleH) && i in padLeft until (padLeft + scaleW)) {
                    // 映射到原始图像坐标
                    val srcY = ((j - padTop) * yRatio).toInt()
                    val srcX = ((i - padLeft) * xRatio).toInt()

                    // NV21 的 Y 分量索引
                    val yIndex = srcY * srcW + srcX
                    // NV21 的 UV 分量索引（交错排列）
                    val uvIndex = frameSize + (srcY shr 1) * srcW + (srcX and 1.inv())

                    // YUV → RGB 转换（BT.601 标准）
                    val y = ((nv21[yIndex].toInt() and 0xFF) - 16).coerceAtLeast(0)
                    val v = (nv21[uvIndex].toInt() and 0xFF) - 128
                    val u = (nv21[uvIndex + 1].toInt() and 0xFF) - 128

                    val r = clamp(1.164f * y + 1.596f * v)
                    val g = clamp(1.164f * y - 0.813f * v - 0.391f * u)
                    val b = clamp(1.164f * y + 2.018f * u)

                    // 写入归一化后的 RGB 值（每通道独立 mean/std）
                    outputArray[outIdx++] = (r / 255f - mean.first) * invStdR
                    outputArray[outIdx++] = (g / 255f - mean.second) * invStdG
                    outputArray[outIdx++] = (b / 255f - mean.third) * invStdB
                } else {
                    // 当前像素在填充区域，使用 padColor 并归一化
                    outputArray[outIdx++] = (padColor.first / 255f - mean.first) * invStdR
                    outputArray[outIdx++] = (padColor.second / 255f - mean.second) * invStdG
                    outputArray[outIdx++] = (padColor.third / 255f - mean.third) * invStdB
                }
            }
        }
    }

    // 限制 RGB 值在 [0, 255] 范围内
    private fun clamp(v: Float) = v.coerceIn(0f, 255f)

    companion object {
        /**
         * 快捷构造函数：ImageNet 标准归一化
         * mean = [0.485, 0.456, 0.406]
         * std  = [0.229, 0.224, 0.225]
         */
        fun imagenet(dstW: Int, dstH: Int): NV21Preprocessor {
            return NV21Preprocessor(
                dstW,
                dstH,
                mean = Triple(0.485f, 0.456f, 0.406f),
                std = Triple(0.229f, 0.224f, 0.225f)
            )
        }
    }
}