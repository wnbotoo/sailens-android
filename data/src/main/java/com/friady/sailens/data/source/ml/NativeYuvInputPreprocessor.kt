package com.friady.sailens.data.source.ml

import com.friady.sailens.data.source.ml.semantic.SegmenterConfig
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.ImagePixelFormat

internal class NativeYuvInputPreprocessor(
    private val config: SegmenterConfig,
    private val inputQuantization: ModelInputQuantization,
) {
    fun preprocessFloat(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: FloatArray,
    ): Boolean {
        val yuv = frame.takeYuvDataOrNull() ?: return false
        if (!NativeMlLibrary.isAvailable) return false
        if (outputArray.size != config.inputWidth * config.inputHeight * 3) return false

        return runCatching {
            nativePreprocessYuvToFloat(
                y = yuv.y.bytes,
                u = yuv.u.bytes,
                v = yuv.v.bytes,
                yRowStride = yuv.y.rowStride,
                yPixelStride = yuv.y.pixelStride,
                uRowStride = yuv.u.rowStride,
                uPixelStride = yuv.u.pixelStride,
                vRowStride = yuv.v.rowStride,
                vPixelStride = yuv.v.pixelStride,
                sourceWidth = frame.width,
                sourceHeight = frame.height,
                rotationDegrees = rotationDegrees,
                targetWidth = config.inputWidth,
                targetHeight = config.inputHeight,
                meanR = config.mean.first,
                meanG = config.mean.second,
                meanB = config.mean.third,
                stdR = config.std.first,
                stdG = config.std.second,
                stdB = config.std.third,
                output = outputArray,
            )
        }.getOrDefault(false)
    }

    fun preprocessInt8(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: ByteArray,
    ): Boolean {
        val yuv = frame.takeYuvDataOrNull() ?: return false
        if (!NativeMlLibrary.isAvailable) return false
        if (outputArray.size != config.inputWidth * config.inputHeight * 3) return false

        return runCatching {
            nativePreprocessYuvToInt8(
                y = yuv.y.bytes,
                u = yuv.u.bytes,
                v = yuv.v.bytes,
                yRowStride = yuv.y.rowStride,
                yPixelStride = yuv.y.pixelStride,
                uRowStride = yuv.u.rowStride,
                uPixelStride = yuv.u.pixelStride,
                vRowStride = yuv.v.rowStride,
                vPixelStride = yuv.v.pixelStride,
                sourceWidth = frame.width,
                sourceHeight = frame.height,
                rotationDegrees = rotationDegrees,
                targetWidth = config.inputWidth,
                targetHeight = config.inputHeight,
                meanR = config.mean.first,
                meanG = config.mean.second,
                meanB = config.mean.third,
                stdR = config.std.first,
                stdG = config.std.second,
                stdB = config.std.third,
                quantScale = inputQuantization.scale,
                quantZeroPoint = inputQuantization.zeroPoint,
                output = outputArray,
            )
        }.getOrDefault(false)
    }

    private fun ImageFrame.takeYuvDataOrNull() =
        yuvData?.takeIf { pixelFormat == ImagePixelFormat.YUV_420_888 }

    private external fun nativePreprocessYuvToFloat(
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        yRowStride: Int,
        yPixelStride: Int,
        uRowStride: Int,
        uPixelStride: Int,
        vRowStride: Int,
        vPixelStride: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        targetWidth: Int,
        targetHeight: Int,
        meanR: Float,
        meanG: Float,
        meanB: Float,
        stdR: Float,
        stdG: Float,
        stdB: Float,
        output: FloatArray,
    ): Boolean

    private external fun nativePreprocessYuvToInt8(
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        yRowStride: Int,
        yPixelStride: Int,
        uRowStride: Int,
        uPixelStride: Int,
        vRowStride: Int,
        vPixelStride: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        targetWidth: Int,
        targetHeight: Int,
        meanR: Float,
        meanG: Float,
        meanB: Float,
        stdR: Float,
        stdG: Float,
        stdB: Float,
        quantScale: Float,
        quantZeroPoint: Int,
        output: ByteArray,
    ): Boolean
}
