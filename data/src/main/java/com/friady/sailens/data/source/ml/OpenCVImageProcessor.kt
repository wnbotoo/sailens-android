package com.friady.sailens.data.source.ml

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.friady.sailens.data.source.ml.segmentation.SegmenterConfig
import com.friady.sailens.domain.model.perception.ImageFrame
import org.opencv.android.Utils
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
) : AutoCloseable {
    private var reusableBitmap: Bitmap? = null

    init {
        OpenCVNativeLoader().init()
    }

    // --- 预处理复用对象 ---
    private val inputMatAbgr = Mat()
    private val inputMatRgb = Mat()
    private val rotatedInput = Mat()
    private val scaledImage = Mat()
    private val paddedImage = Mat()
    private val normalizedImage = Mat()

    /**
     * 预处理：ImageFrame -> Bitmap -> Mat -> Rotate -> Letterbox Resize -> Normalize -> FloatArray
     */
    fun preprocess(frame: ImageFrame, rotationDegrees: Int, outputArray: FloatArray) {
        val bitmap = obtainBitmap(frame.width, frame.height)
        bitmap.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)

        // 1. Bitmap -> Mat (BGRA)
        Utils.bitmapToMat(bitmap, inputMatAbgr)

        // 2. 转为 RGB
        Imgproc.cvtColor(inputMatAbgr, inputMatRgb, Imgproc.COLOR_BGRA2RGB)

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
    fun postprocess(outputFloatArray: FloatArray, outputMask: IntArray) {
        val h = config.outputHeight
        val w = config.outputWidth
        val c = config.outputChannels

        // 数据布局: [H, W, C] -> 索引 = (row * W + col) * C + channel
        for (row in 0 until h) {
            for (col in 0 until w) {
                val baseIdx = (row * w + col) * c
                var maxIdx = 0
                var maxVal = outputFloatArray[baseIdx]

                for (ch in 1 until c) {
                    val value = outputFloatArray[baseIdx + ch]
                    if (value > maxVal) {
                        maxVal = value
                        maxIdx = ch
                    }
                }

                outputMask[row * w + col] = maxIdx
            }
        }
    }

    private fun rotateMat(src: Mat, dst: Mat, angle: Int) {
        when (angle) {
            90 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            else -> src.copyTo(dst)
        }
    }

    override fun close() {
        reusableBitmap?.recycle()
        reusableBitmap = null
        inputMatAbgr.release()
        inputMatRgb.release()
        rotatedInput.release()
        scaledImage.release()
        paddedImage.release()
        normalizedImage.release()
    }

    private fun obtainBitmap(width: Int, height: Int): Bitmap {
        val current = reusableBitmap
        if (current != null && current.width == width && current.height == height) {
            return current
        }

        current?.recycle()
        return createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            reusableBitmap = it
        }
    }
}

// 优化版本，但是性能略微低于上面
//class OpenCVImageProcessor(
//    private val config: SegmenterConfig,
//) : AutoCloseable {
//
//    init {
//        OpenCVNativeLoader().init()
//    }
//
//    // --- Pre-allocated Reusable Objects ---
//    private val inputMatAbgr = Mat()
//    private val inputMatRgb = Mat()
//    private val finalImage = Mat()
//    private val normalizedImage = Mat()
//    // Initialize with 2 rows, 3 cols, and type CV_64F (Double)
//    // Do not use default constructor Mat() creates an empty matrix (0x0), causing the assertion failure.
//    private val transMat = Mat(2, 3, CvType.CV_64F)
//
//    /**
//     * Preprocessing: Fused operation (Rotate + Resize + Pad) -> Normalize -> FloatArray
//     * Uses warpAffine to perform geometric transformations in a single pass.
//     */
//    fun preprocess(bitmap: Bitmap, rotationDegrees: Int, outputArray: FloatArray) {
//        // 1. Bitmap -> Mat (BGRA)
//        Utils.bitmapToMat(bitmap, inputMatAbgr)
//
//        // 2. Convert to RGB
//        Imgproc.cvtColor(inputMatAbgr, inputMatRgb, Imgproc.COLOR_BGRA2RGB)
//
//        // 3. Calculate Affine Matrix for "Letterbox" (Scale + Center + Rotate)
//        val srcW = inputMatRgb.cols().toDouble()
//        val srcH = inputMatRgb.rows().toDouble()
//        val dstW = config.inputWidth.toDouble()
//        val dstH = config.inputHeight.toDouble()
//
//        // Determine dimensions after rotation logic for scaling calculation
//        val isRotated90 = rotationDegrees == 90 || rotationDegrees == 270
//        val effectiveSrcW = if (isRotated90) srcH else srcW
//        val effectiveSrcH = if (isRotated90) srcW else srcH
//
//        val scale = min(dstW / effectiveSrcW, dstH / effectiveSrcH)
//
//        // Get rotation matrix around the center of the source image
//        val center = Point(srcW / 2.0, srcH / 2.0)
//        val rotMat = Imgproc.getRotationMatrix2D(center, -rotationDegrees.toDouble(), scale)
//
//        // Adjust translation to center the image in the destination
//        val rotArr = DoubleArray(6)
//        rotMat.get(0, 0, rotArr)
//
//        // Calculate where the center moves to
//        val newCenterX = rotArr[0] * center.x + rotArr[1] * center.y + rotArr[2]
//        val newCenterY = rotArr[3] * center.x + rotArr[4] * center.y + rotArr[5]
//
//        // Update translation part of the matrix
//        rotArr[2] += (dstW / 2.0) - newCenterX
//        rotArr[5] += (dstH / 2.0) - newCenterY
//
//        // Populate the pre-allocated transformation matrix
//        transMat.put(0, 0, *rotArr)
//        rotMat.release()
//
//        // 4. Apply Warp Affine (Rotate, Resize, Pad in one step)
//        Imgproc.warpAffine(
//            inputMatRgb,
//            finalImage,
//            transMat,
//            Size(dstW, dstH),
//            Imgproc.INTER_LINEAR,
//            Core.BORDER_CONSTANT,
//            Scalar(0.0, 0.0, 0.0)
//        )
//
//        // 5. Normalize (0-255 -> 0.0-1.0) and convert to Float32
//        finalImage.convertTo(normalizedImage, CvType.CV_32FC3, 1.0 / 255.0)
//
//        // 6. Extract data to FloatArray
//        normalizedImage.get(0, 0, outputArray)
//    }
//
//    /**
//     * Postprocessing: FloatArray -> ArgMax -> IntArray
//     * Optimized with Kotlin Coroutines for parallel execution
//     */
//    suspend fun postprocess(outputFloatArray: FloatArray, outputMask: IntArray) {
//        val h = config.outputHeight
//        val w = config.outputWidth
//        val c = config.outputChannels
//
//        // 计算最优的分块大小
//        val numCores = Runtime.getRuntime().availableProcessors()
//        val chunkSize = calculateOptimalChunkSize(h, numCores)
//
//        withContext(Dispatchers.Default) {
//            // 将工作按行分块，每个协程处理一个chunk
//            (0 until h step chunkSize).map { startRow ->
//                async {
//                    val endRow = min(startRow + chunkSize, h)
//                    processRowRange(
//                        outputFloatArray,
//                        outputMask,
//                        startRow,
//                        endRow,
//                        w,
//                        c
//                    )
//                }
//            }.awaitAll()
//        }
//    }
//
//    /**
//     * 同步版本的postprocess（用于不支持协程的场景）
//     */
//    fun postprocessBlocking(outputFloatArray: FloatArray, outputMask: IntArray) {
//        runBlocking {
//            postprocess(outputFloatArray, outputMask)
//        }
//    }
//
//    /**
//     * 处理指定范围的行
//     */
//    private fun processRowRange(
//        outputFloatArray: FloatArray,
//        outputMask: IntArray,
//        startRow: Int,
//        endRow: Int,
//        width: Int,
//        channels: Int
//    ) {
//        for (row in startRow until endRow) {
//            for (col in 0 until width) {
//                val baseIdx = (row * width + col) * channels
//                var maxIdx = 0
//                var maxVal = outputFloatArray[baseIdx]
//
//                // 内循环展开优化（每次处理4个channel）
//                var ch = 1
//                val unrollLimit = channels - 3
//
//                // 循环展开：每次处理4个
//                while (ch < unrollLimit) {
//                    val v0 = outputFloatArray[baseIdx + ch]
//                    val v1 = outputFloatArray[baseIdx + ch + 1]
//                    val v2 = outputFloatArray[baseIdx + ch + 2]
//                    val v3 = outputFloatArray[baseIdx + ch + 3]
//
//                    if (v0 > maxVal) {
//                        maxVal = v0
//                        maxIdx = ch
//                    }
//                    if (v1 > maxVal) {
//                        maxVal = v1
//                        maxIdx = ch + 1
//                    }
//                    if (v2 > maxVal) {
//                        maxVal = v2
//                        maxIdx = ch + 2
//                    }
//                    if (v3 > maxVal) {
//                        maxVal = v3
//                        maxIdx = ch + 3
//                    }
//
//                    ch += 4
//                }
//
//                // 处理剩余的channel
//                while (ch < channels) {
//                    val value = outputFloatArray[baseIdx + ch]
//                    if (value > maxVal) {
//                        maxVal = value
//                        maxIdx = ch
//                    }
//                    ch++
//                }
//
//                outputMask[row * width + col] = maxIdx
//            }
//        }
//    }
//
//    /**
//     * 计算最优分块大小
//     * 考虑因素：CPU核心数、缓存行大小、总行数
//     */
//    private fun calculateOptimalChunkSize(totalRows: Int, numCores: Int): Int {
//        // 每个核心至少分配的行数
//        val minRowsPerCore = 4
//
//        // 理想情况下，分块数略多于核心数（1.5-2倍），以提高负载均衡
//        val idealChunks = (numCores * 1.5).toInt()
//
//        val chunkSize = totalRows / idealChunks
//
//        // 确保chunk不会太小（影响效率）或太大（影响并行度）
//        return chunkSize.coerceIn(minRowsPerCore, totalRows / numCores + 1)
//    }
//
//    override fun close() {
//        inputMatAbgr.release()
//        inputMatRgb.release()
//        finalImage.release()
//        normalizedImage.release()
//        transMat.release()
//    }
//}