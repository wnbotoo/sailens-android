package com.friady.sailens.data.source.ml

import android.graphics.Bitmap
import com.friady.sailens.data.source.ml.segmentation.SegmenterConfig

/**
 * LiteRT 图像处理器兼容层。
 *
 * 当前主链路统一走 `OpenCVImageProcessor`，这里保留相同 API，避免未来切换实现时影响调用方。
 */
class LiteRTImageProcessor(config: SegmenterConfig) : AutoCloseable {
    private val delegate = OpenCVImageProcessor(config)

    /**
     * 预处理：Bitmap -> 旋转 -> 缩放 -> 归一化 -> FloatArray
     *
     * @param bitmap 输入的位图
     * @param rotationDegrees 旋转角度 (0, 90, 180, 270)
     * @return 模型输入的 FloatArray，大小为 inputHeight * inputWidth * 3
     */
    fun preprocess(bitmap: Bitmap, rotationDegrees: Int, outputArray: FloatArray) {
        delegate.preprocess(bitmap, rotationDegrees, outputArray)
    }

    /**
     * 后处理：将模型输出的分数数组转换为分割掩码
     *
     * @param scores 模型输出的扁平化分数数组 (outputHeight * outputWidth * outputChannels)
     * @param resultMask 输出的分割掩码，大小为 outputHeight * outputWidth，每个像素对应类别索引或 -1 (低置信度)
     */
    fun postprocess(scores: FloatArray, resultMask: IntArray) {
        delegate.postprocess(scores, resultMask)
    }

    override fun close() {
        delegate.close()
    }
}