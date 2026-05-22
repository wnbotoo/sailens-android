package com.friady.sailens.data.source.ml

import com.friady.sailens.data.source.ml.semantic.SegmenterConfig
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.ImagePixelFormat
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.osgi.OpenCVNativeLoader
import kotlin.math.min

class OpenCVImageProcessor(
    private val config: SegmenterConfig,
) : FramePreprocessor {
    init {
        OpenCVNativeLoader().init()
    }

    // --- 预处理复用对象 ---
    private val inputMatRgba = Mat()
    private val inputMatRgb = Mat()
    private val rotatedInput = Mat()
    private val scaledImage = Mat()
    private val paddedImage = Mat()
    private val normalizedImage = Mat()

    /**
     * 预处理：ImageFrame RGBA bytes -> Mat -> Rotate -> Letterbox Resize -> Normalize -> FloatArray
     */
    override fun preprocess(frame: ImageFrame, rotationDegrees: Int, outputArray: FloatArray) {
        require(frame.pixelFormat == ImagePixelFormat.RGBA_8888) {
            "Unsupported frame pixel format ${frame.pixelFormat}"
        }
        require(frame.pixelBytes.size >= frame.width * frame.height * frame.pixelFormat.bytesPerPixel) {
            "Frame pixel buffer is smaller than ${frame.width}x${frame.height} ${frame.pixelFormat}"
        }

        // 1. RGBA bytes -> Mat
        inputMatRgba.create(frame.height, frame.width, CvType.CV_8UC4)
        inputMatRgba.put(0, 0, frame.pixelBytes)

        // 2. 转为 RGB
        Imgproc.cvtColor(inputMatRgba, inputMatRgb, Imgproc.COLOR_RGBA2RGB)

        // 3. 旋转
        rotateMat(inputMatRgb, rotatedInput, rotationDegrees)

        // 4. Letterbox: 保持宽高比缩放 + 居中填充
        val srcWidth = rotatedInput.cols()
        val srcHeight = rotatedInput.rows()
        val targetWidth = config.inputWidth
        val targetHeight = config.inputHeight

        val scale = min(
            targetWidth.toFloat() / srcWidth,
            targetHeight.toFloat() / srcHeight
        )
        val newWidth = (srcWidth * scale).toInt()
        val newHeight = (srcHeight * scale).toInt()

        Imgproc.resize(rotatedInput, scaledImage, Size(newWidth.toDouble(), newHeight.toDouble()))

        // 创建黑色背景并居中放置缩放后的图像
        paddedImage.create(targetHeight, targetWidth, scaledImage.type())
        paddedImage.setTo(Scalar(0.0, 0.0, 0.0))

        val offsetX = (targetWidth - newWidth) / 2
        val offsetY = (targetHeight - newHeight) / 2
        val roi = paddedImage.submat(offsetY, offsetY + newHeight, offsetX, offsetX + newWidth)
        scaledImage.copyTo(roi)
        roi.release()  // 释放 submat 引用

        // 5. 归一化 (0-255 -> 0.0-1.0) 并转为 Float32
        paddedImage.convertTo(normalizedImage, CvType.CV_32FC3, 1.0 / 255.0)

        // 6. 提取数据到 FloatArray
        normalizedImage.get(0, 0, outputArray)
    }

    /**
     * 后处理：FloatArray -> ArgMax -> IntArray
     * 手动实现 argmax，因为 OpenCV 的 reduceArgMax 对 3D Mat 支持有问题
     */
    override fun postprocess(scores: FloatArray, resultMask: IntArray) {
        val h = config.outputHeight
        val w = config.outputWidth
        val c = config.outputChannels

        // 数据布局: [H, W, C] -> 索引 = (row * W + col) * C + channel
        for (row in 0 until h) {
            for (col in 0 until w) {
                val baseIdx = (row * w + col) * c
                var maxIdx = 0
                var maxVal = scores[baseIdx]

                for (ch in 1 until c) {
                    val value = scores[baseIdx + ch]
                    if (value > maxVal) {
                        maxVal = value
                        maxIdx = ch
                    }
                }

                resultMask[row * w + col] = maxIdx
            }
        }
    }

    private fun rotateMat(src: Mat, dst: Mat, angle: Int) {
        when (angle) {
            90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> src.copyTo(dst)
        }
    }

    override fun close() {
        inputMatRgba.release()
        inputMatRgb.release()
        rotatedInput.release()
        scaledImage.release()
        paddedImage.release()
        normalizedImage.release()
    }
}
