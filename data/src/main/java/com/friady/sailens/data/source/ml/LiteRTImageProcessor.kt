package com.friady.sailens.data.source.ml

import android.graphics.Bitmap
import com.friady.sailens.data.source.ml.segmentation.SegmenterConfig
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.image.ops.Rot90Op
import java.nio.FloatBuffer
import kotlin.collections.get
import kotlin.math.min

/**
 * 图像预处理与后处理工具类：
 * - preprocess: 将 NV21 数据转换为模型输入格式的 FloatArray
 *   - 支持缩放到目标尺寸并居中填充
 *   - 支持每通道独立的 mean/std 归一化
 * - postprocess: 将模型输出的分数数组转换为分割掩码
 *   - 支持 argmax 选择类别
 *   - 支持 confidenceThreshold，低置信度像素标记为 void(-1)
 */
class LiteRTImageProcessor(private val config: SegmenterConfig) : AutoCloseable {

    // 移除全局缓存的 processor，因为 padding 需要根据每张图的尺寸动态计算（如果输入尺寸会变）
    // 如果输入尺寸固定是 1920x1080，可以缓存，但为了通用性，这里展示动态构建或基于尺寸缓存
    private var cachedProcessor: ImageProcessor? = null
    private var lastInputWidth: Int = -1
    private var lastInputHeight: Int = -1
    private var lastRotation: Int = -1

    // 缓存 TensorImage 避免重复创建
    private val tensorImage = TensorImage(DataType.FLOAT32)

    // 缓存输出 FloatBuffer，避免 floatArray 每次复制
    private var cachedFloatBuffer: FloatBuffer? = null

    /**
     * 预处理：Bitmap -> 旋转 -> 缩放 -> 归一化 -> FloatArray
     *
     * @param bitmap 输入的位图
     * @param rotationDegrees 旋转角度 (0, 90, 180, 270)
     * @return 模型输入的 FloatArray，大小为 inputHeight * inputWidth * 3
     */
    @Synchronized
    fun preprocess(bitmap: Bitmap, rotationDegrees: Int, outputArray: FloatArray) {
        val width = bitmap.width
        val height = bitmap.height

        // 如果输入尺寸或旋转角度发生变化，重新构建 Processor
        if (cachedProcessor == null || width != lastInputWidth || height != lastInputHeight || rotationDegrees != lastRotation) {
            cachedProcessor = buildProcessor(width, height, rotationDegrees)
            lastInputWidth = width
            lastInputHeight = height
            lastRotation = rotationDegrees
        }

        tensorImage.load(bitmap)
        val outputBuffer = cachedProcessor!!.process(tensorImage).tensorBuffer.buffer.asFloatBuffer()

        outputBuffer.rewind()
        outputBuffer.get(outputArray)

    }

    private fun buildProcessor(inputWidth: Int, inputHeight: Int, degrees: Int): ImageProcessor {
        val builder = ImageProcessor.Builder()

        // 1. 处理旋转
        if (degrees != 0) {
            builder.add(Rot90Op(-degrees / 90))
        }

        // 2. 保持比例缩放 (Letterbox / Padding)
        // 目标: 2048 x 1024
        val targetHeight = config.inputHeight
        val targetWidth = config.inputWidth

        // 计算缩放后的尺寸，保持长宽比
        // 注意：这里需要考虑旋转后的宽高
        val isRotated = (degrees / 90) % 2 != 0
        val inW = if (isRotated) inputHeight else inputWidth
        val inH = if (isRotated) inputWidth else inputHeight

        val scale = min(targetWidth.toFloat() / inW, targetHeight.toFloat() / inH)
        val scaledWidth = (inW * scale).toInt()
        val scaledHeight = (inH * scale).toInt()

        // A. 先缩放到能塞进目标框的最大尺寸 (保持比例)
        builder.add(ResizeOp(scaledHeight, scaledWidth, ResizeOp.ResizeMethod.BILINEAR))

        // B. 再填充到目标尺寸 (居中填充)
        // ResizeWithCropOrPadOp 如果目标尺寸比当前大，就会进行填充(Pad)
        builder.add(ResizeWithCropOrPadOp(targetHeight, targetWidth))

        // 3. 归一化
        builder.add(
            NormalizeOp(
                floatArrayOf(
                    config.mean.first * 255f,
                    config.mean.second * 255f,
                    config.mean.third * 255f
                ),
                floatArrayOf(
                    config.std.first * 255f,
                    config.std.second * 255f,
                    config.std.third * 255f
                )
            )
        )

        return builder.build()
    }

    /**
     * 后处理：将模型输出的分数数组转换为分割掩码
     *
     * @param scores 模型输出的扁平化分数数组 (outputHeight * outputWidth * outputChannels)
     * @param resultMask 输出的分割掩码，大小为 outputHeight * outputWidth，每个像素对应类别索引或 -1 (低置信度)
     */
    fun postprocess(scores: FloatArray, resultMask: IntArray) {
        val h = config.outputHeight
        val w = config.outputWidth
        val numClasses = config.outputChannels
        val threshold = config.confidenceThreshold

        for (i in 0 until h * w) {
            var maxScore = Float.NEGATIVE_INFINITY
            var maxClass = -1
            val offset = i * numClasses

            for (c in 0 until numClasses) {
                val score = scores[offset + c]
                if (score > maxScore) {
                    maxScore = score
                    maxClass = c
                }
            }

            resultMask[i] = if (maxScore >= threshold) maxClass else -1
        }
    }

    override fun close() {
        cachedProcessor = null
        cachedFloatBuffer = null
    }
}