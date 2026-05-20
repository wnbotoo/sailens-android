package com.friady.sailens.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.friady.sailens.domain.model.perception.ImageFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "ImageFrameAnalyzer"

class ImageFrameAnalyzer() : ImageAnalysis.Analyzer, ImageFrameProvider {

    private val _frames = MutableSharedFlow<ImageFrame>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val frames: SharedFlow<ImageFrame> = _frames.asSharedFlow()

    override fun analyze(image: ImageProxy) {
        try {
            val bitmap = image.toBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()

            val frame = ImageFrame(
                width = image.width,
                height = image.height,
                pixels = pixels,
                rotationDegrees = image.imageInfo.rotationDegrees,
                timestamp = image.imageInfo.timestamp
            )
            _frames.tryEmit(frame)
        } finally {
            image.close()
        }
    }
}

//class ImageFrameAnalyzer(
//    private val deviceSensorRepository: DeviceSensorRepository,
//) : ImageAnalysis.Analyzer, ImageFrameProvider {
//
//    private val _frames = MutableSharedFlow<ImageFrame>(
//        extraBufferCapacity = 8,
//        onBufferOverflow = BufferOverflow.DROP_OLDEST
//    )
//    override val frames: SharedFlow<ImageFrame> = _frames.asSharedFlow()
//
//    override fun analyze(image: ImageProxy) {
//        try {
//            image.toFrame()?.let { frame ->
//                val success = _frames.tryEmit(frame)
//            }
//        } finally {
//            image.close()
//        }
//    }
//
//    @OptIn(ExperimentalGetImage::class)
//    private fun ImageProxy.toFrame(): ImageFrame? {
//        val image = this.image ?: return null
//        // These are dimensions from the sensor's perspective, before any rotation is applied by your code.
//        val sensorWidth = width
//        val sensorHeight = height
//
//        var nv21 = yuv420888ToNv21(image)
//
//        val finalWidth: Int
//        val finalHeight: Int
//        val rotationDegrees = imageInfo.rotationDegrees
//        val deviceRotationDegree = deviceSensorRepository.deviceRotationDegree
//
//
//        nv21 = rotateNV21(nv21, sensorWidth, sensorHeight, rotationDegrees / 90)
//        // If rotated by 90 or 270 degrees, the dimensions are swapped
//        if (deviceRotationDegree == 0 || deviceRotationDegree == 180) {
//            finalWidth = sensorHeight
//            finalHeight = sensorWidth
//        } else {
//            finalWidth = sensorWidth
//            finalHeight = sensorHeight
//        }
//
//        Log.d(
//            TAG,
//            "ImageAnalysis frame original sensor size ${sensorWidth}x${sensorHeight}, " +
//                    "sensorRotation: $rotationDegrees, " +
//                    "deviceRotationDegree: $deviceRotationDegree, " +
//                    "final Frame size ${finalWidth}x${finalHeight}"
//        )
//
//        return ImageFrame(
//            finalWidth,
//            finalHeight,
//            nv21,
//            imageInfo.timestamp
//        )
//    }
//
//    /**
//     * Converts an ImageProxy (YUV_420_888 format) into a standard NV21 (VUVU) ByteArray.
//     * This correctly handles the rowStride and pixelStride of the planes.
//     *
//     * @param image The ImageProxy from CameraX ImageAnalysis.
//     * @return The NV21 formatted ByteArray.
//     */
//    fun yuv420888ToNv21(image: Image): ByteArray {
//        // Ensure the format is correct, though ImageAnalysis usually guarantees this
//        require(image.format == ImageFormat.YUV_420_888) { "Invalid image format" }
//
//        val width = image.width
//        val height = image.height
//        val planes = image.planes
//
//        // Y (0), U (1), V (2) planes
//        val yPlane = planes[0]
//        val uPlane = planes[1]
//        val vPlane = planes[2]
//
//        val yBuffer = yPlane.buffer
//        val uBuffer = uPlane.buffer
//        val vBuffer = vPlane.buffer
//
//        // Rewind buffers to ensure we start reading from the beginning
//        yBuffer.rewind()
//        uBuffer.rewind()
//        vBuffer.rewind()
//
//        val ySize = width * height
//        val uvSize = ySize / 2
//        val nv21 = ByteArray(ySize + uvSize)
//
//        // --- 1. Copy Y Plane (Luma) ---
//        val yRowStride = yPlane.rowStride
//        var yArrayOffset = 0
//
//        // Iterate over each row of the Y plane
//        for (i in 0 until height) {
//            // Read only 'width' bytes of actual data for this row
//            val rowBytes = width
//
//            // Copy the row data to the NV21 array
//            yBuffer.get(nv21, yArrayOffset, rowBytes)
//            yArrayOffset += rowBytes
//
//            // Skip the padding bytes (if any) in the source buffer
//            // Note: We only skip if it's not the last row, or if the buffer has padding left
//            if (i < height - 1) {
//                val padding = yRowStride - width
//                if (padding > 0) {
//                    // Move the buffer position past the padding bytes
//                    yBuffer.position(yBuffer.position() + padding)
//                }
//            }
//        }
//
//        // --- 2. Copy UV Plane (Chroma) - Interleaved V U V U... (NV21 format) ---
//        val chromaWidth = width / 2
//        val chromaHeight = height / 2
//
//        // Get strides for U and V planes
//        val uPixelStride = uPlane.pixelStride
//        val vPixelStride = vPlane.pixelStride
//        val uRowStride = uPlane.rowStride
//        val vRowStride = vPlane.rowStride
//
//        var uvArrayOffset = ySize
//
//        // Iterate over chroma rows
//        for (i in 0 until chromaHeight) {
//
//            // Calculate the starting index for this row in the source buffers
//            var uBufferIndex = i * uRowStride
//            var vBufferIndex = i * vRowStride
//
//            // Interleave V and U components for the current row
//            for (j in 0 until chromaWidth) {
//
//                // Check array bounds before writing
//                if (uvArrayOffset >= nv21.size) break
//
//                // V component (NV21 requires V first)
//                // Use .get(index) and check against buffer limit to prevent exceptions
//                if (vBufferIndex < vBuffer.limit()) {
//                    nv21[uvArrayOffset++] = vBuffer.get(vBufferIndex)
//                    vBufferIndex += vPixelStride
//                }
//
//                // U component
//                if (uBufferIndex < uBuffer.limit()) {
//                    nv21[uvArrayOffset++] = uBuffer.get(uBufferIndex)
//                    uBufferIndex += uPixelStride
//                }
//            }
//        }
//
//        return nv21
//    }
//
//    /**
//     * 旋转 NV21 数据（每次以 90° 为单位）
//     *
//     * @param data NV21 原始字节数据
//     * @param width 原始图像宽
//     * @param height 原始图像高
//     * @param numQuarter 旋转的90°次数
//     *                   正数 = 顺时针，负数 = 逆时针
//     *                   例：1=90°顺时针，-1=90°逆时针，2=180°
//     */
//    fun rotateNV21(data: ByteArray, width: Int, height: Int, numQuarter: Int): ByteArray {
//        val effective = ((numQuarter % 4) + 4) % 4
//        if (effective == 0) return data // 不旋转
//
//        val wh = width * height
//        val out = ByteArray(data.size)
//
//        when (effective) {
//            1 -> { // 顺时针90°
//                var i = 0
//                for (x in 0 until width) {
//                    for (y in height - 1 downTo 0) {
//                        out[i++] = data[y * width + x]
//                    }
//                }
//                i = wh
//                for (x in 0 until width step 2) {
//                    for (y in (height / 2) - 1 downTo 0) {
//                        val index = wh + y * width + x
//                        out[i++] = data[index]
//                        out[i++] = data[index + 1]
//                    }
//                }
//            }
//
//            2 -> { // 180°
//                var i = 0
//                for (y in height - 1 downTo 0) {
//                    for (x in width - 1 downTo 0) {
//                        out[i++] = data[y * width + x]
//                    }
//                }
//                for (y in (height / 2) - 1 downTo 0) {
//                    for (x in width - 2 downTo 0 step 2) {
//                        val index = wh + y * width + x
//                        out[i++] = data[index]
//                        out[i++] = data[index + 1]
//                    }
//                }
//            }
//
//            3 -> { // 逆时针90°（等于顺时针270°）
//                var i = 0
//                for (x in 0 until width) {
//                    for (y in height - 1 downTo 0) {
//                        out[i++] = data[y * width + x]
//                    }
//                }
//                i = wh
//                for (x in 0 until width step 2) {
//                    for (y in (height / 2) - 1 downTo 0) {
//                        val index = wh + y * width + x
//                        out[i++] = data[index]
//                        out[i++] = data[index + 1]
//                    }
//                }
//            }
//        }
//        return out
//    }
//}
