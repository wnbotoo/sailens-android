package com.sailens.data.source.ml

import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.frame.Yuv420FrameData
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.osgi.OpenCVNativeLoader
import kotlin.math.min

class OpenCVImageProcessor(
    private val config: ModelTensorConfig,
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
    private var rgbaScratch = ByteArray(0)
    private var nhwcFloatScratch = FloatArray(0)

    /**
     * 预处理：ImageFrame -> RGB Mat -> Rotate -> Letterbox Resize -> Normalize -> FloatArray
     */
    override fun preprocess(frame: ImageFrame, rotationDegrees: Int, outputArray: FloatArray) {
        val rgbaBytes = frame.toRgbaBytes()

        // 1. RGBA bytes -> Mat
        inputMatRgba.create(frame.height, frame.width, CvType.CV_8UC4)
        inputMatRgba.put(0, 0, rgbaBytes)

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

        Imgproc.resize(
            rotatedInput,
            scaledImage,
            Size(newWidth.toDouble(), newHeight.toDouble()),
            0.0,
            0.0,
            config.resizeFilter.toOpenCvInterpolation(),
        )

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

        // 6. 提取数据到模型输入布局
        val expectedSize = targetWidth * targetHeight * CHANNELS_RGB
        require(outputArray.size == expectedSize) {
            "Output array size ${outputArray.size} does not match model input element count $expectedSize"
        }
        when (config.inputLayout) {
            ImageTensorLayout.NHWC -> normalizedImage.get(0, 0, outputArray)
            ImageTensorLayout.NCHW -> {
                val nhwc = nhwcFloatScratch.withMinSize(expectedSize).also {
                    nhwcFloatScratch = it
                }
                normalizedImage.get(0, 0, nhwc)
                nhwc.copyHwcToNchw(
                    output = outputArray,
                    width = targetWidth,
                    height = targetHeight,
                    channels = CHANNELS_RGB,
                )
            }
        }
    }

    /**
     * 后处理：FloatArray -> ArgMax -> IntArray
     * 手动实现 argmax，因为 OpenCV 的 reduceArgMax 对 3D Mat 支持有问题
     */
    override fun postprocess(scores: FloatArray, resultMask: IntArray) {
        val h = config.outputHeight
        val w = config.outputWidth
        val c = config.outputChannels

        val pixelCount = h * w
        for (row in 0 until h) {
            for (col in 0 until w) {
                val pixelIndex = row * w + col
                var maxIdx = 0
                var maxVal = scores.scoreAt(pixelIndex, channel = 0, pixelCount, c)

                for (ch in 1 until c) {
                    val value = scores.scoreAt(pixelIndex, ch, pixelCount, c)
                    if (value > maxVal) {
                        maxVal = value
                        maxIdx = ch
                    }
                }

                resultMask[row * w + col] = maxIdx
            }
        }
    }

    private fun FloatArray.scoreAt(
        pixelIndex: Int,
        channel: Int,
        pixelCount: Int,
        channels: Int,
    ): Float {
        return when (config.outputLayout) {
            ImageTensorLayout.NHWC -> this[pixelIndex * channels + channel]
            ImageTensorLayout.NCHW -> this[channel * pixelCount + pixelIndex]
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

    private fun ImageFrame.toRgbaBytes(): ByteArray {
        return when (pixelFormat) {
            ImagePixelFormat.RGBA_8888 -> {
                require(pixelBytes.size >= width * height * pixelFormat.bytesPerPixel) {
                    "Frame pixel buffer is smaller than ${width}x$height $pixelFormat"
                }
                pixelBytes
            }

            ImagePixelFormat.YUV_420_888 -> {
                val yuv = requireNotNull(yuvData) {
                    "YUV_420_888 frame must include yuvData"
                }
                val requiredSize = width * height * ImagePixelFormat.RGBA_8888.bytesPerPixel
                if (rgbaScratch.size != requiredSize) {
                    rgbaScratch = ByteArray(requiredSize)
                }
                yuv420ToRgba(yuv, width, height, rgbaScratch)
                rgbaScratch
            }
        }
    }

    private fun yuv420ToRgba(
        yuv: Yuv420FrameData,
        width: Int,
        height: Int,
        output: ByteArray,
    ) {
        var outIndex = 0
        for (y in 0 until height) {
            val yRowOffset = y * yuv.y.rowStride
            val uRowOffset = (y / 2) * yuv.u.rowStride
            val vRowOffset = (y / 2) * yuv.v.rowStride

            for (x in 0 until width) {
                val yValue = yuv.y.bytes.unsigned(yRowOffset + x * yuv.y.pixelStride)
                val uvColumnOffset = (x / 2) * yuv.u.pixelStride
                val uValue = yuv.u.bytes.unsigned(uRowOffset + uvColumnOffset)
                val vValue = yuv.v.bytes.unsigned(vRowOffset + (x / 2) * yuv.v.pixelStride)

                val c = (yValue - 16).coerceAtLeast(0)
                val d = uValue - 128
                val e = vValue - 128

                output[outIndex++] = clampToByte((298 * c + 409 * e + 128) shr 8)
                output[outIndex++] = clampToByte((298 * c - 100 * d - 208 * e + 128) shr 8)
                output[outIndex++] = clampToByte((298 * c + 516 * d + 128) shr 8)
                output[outIndex++] = FULL_ALPHA
            }
        }
    }

    private fun ByteArray.unsigned(index: Int): Int {
        require(index in indices) {
            "Plane buffer index $index out of bounds [0, $size)"
        }
        return this[index].toInt() and 0xFF
    }

    private fun clampToByte(value: Int): Byte {
        return value.coerceIn(0, 255).toByte()
    }

    private fun ResizeFilter.toOpenCvInterpolation(): Int {
        return when (this) {
            ResizeFilter.NEAREST -> Imgproc.INTER_NEAREST
            ResizeFilter.BILINEAR -> Imgproc.INTER_LINEAR
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

    private companion object {
        private const val CHANNELS_RGB = 3
        private val FULL_ALPHA: Byte = 0xFF.toByte()
    }
}

private fun FloatArray.withMinSize(size: Int): FloatArray {
    return if (this.size >= size) this else FloatArray(size)
}
