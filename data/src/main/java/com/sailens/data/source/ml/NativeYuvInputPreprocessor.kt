package com.sailens.data.source.ml

import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat

internal class NativeYuvInputPreprocessor(
    private val config: ModelTensorConfig,
    private val inputQuantization: ModelInputQuantization,
) {
    private var nhwcFloatScratch = FloatArray(0)
    private var nhwcInt8Scratch = ByteArray(0)

    fun preprocessFloat(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: FloatArray,
    ): Boolean {
        val yuv = frame.takeYuvDataOrNull() ?: return false
        if (!NativeMlLibrary.isAvailable) return false
        val expectedSize = config.inputWidth * config.inputHeight * CHANNELS_RGB
        if (outputArray.size != expectedSize) return false

        val nativeOutput = when (config.inputLayout) {
            ImageTensorLayout.NHWC -> outputArray
            ImageTensorLayout.NCHW -> nhwcFloatScratch.withMinSize(expectedSize).also {
                nhwcFloatScratch = it
            }
        }

        val success = runCatching {
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
                resizeFilter = config.resizeFilter.nativeValue,
                output = nativeOutput,
            )
        }.getOrDefault(false)
        if (success && config.inputLayout == ImageTensorLayout.NCHW) {
            nativeOutput.copyHwcToNchw(
                output = outputArray,
                width = config.inputWidth,
                height = config.inputHeight,
                channels = CHANNELS_RGB,
            )
        }
        return success
    }

    fun preprocessInt8(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: ByteArray,
    ): Boolean {
        val yuv = frame.takeYuvDataOrNull() ?: return false
        if (!NativeMlLibrary.isAvailable) return false
        val expectedSize = config.inputWidth * config.inputHeight * CHANNELS_RGB
        if (outputArray.size != expectedSize) return false

        val nativeOutput = when (config.inputLayout) {
            ImageTensorLayout.NHWC -> outputArray
            ImageTensorLayout.NCHW -> nhwcInt8Scratch.withMinSize(expectedSize).also {
                nhwcInt8Scratch = it
            }
        }

        val success = runCatching {
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
                resizeFilter = config.resizeFilter.nativeValue,
                quantScale = inputQuantization.scale,
                quantZeroPoint = inputQuantization.zeroPoint,
                output = nativeOutput,
            )
        }.getOrDefault(false)
        if (success && config.inputLayout == ImageTensorLayout.NCHW) {
            nativeOutput.copyHwcToNchw(
                output = outputArray,
                width = config.inputWidth,
                height = config.inputHeight,
                channels = CHANNELS_RGB,
            )
        }
        return success
    }

    /** Native float -> int8 quantization (replaces the Kotlin per-element loop). Returns false when
     * the native lib is unavailable or sizes mismatch, so the caller can fall back. */
    fun quantizeFloatToInt8(input: FloatArray, output: ByteArray): Boolean {
        if (!NativeMlLibrary.isAvailable) return false
        if (input.isEmpty() || input.size != output.size) return false
        return runCatching {
            nativeQuantizeFloatToInt8(input, output, inputQuantization.scale, inputQuantization.zeroPoint)
        }.getOrDefault(false)
    }

    private fun ImageFrame.takeYuvDataOrNull() =
        yuvData?.takeIf { pixelFormat == ImagePixelFormat.YUV_420_888 }

    private external fun nativeQuantizeFloatToInt8(
        input: FloatArray,
        output: ByteArray,
        quantScale: Float,
        quantZeroPoint: Int,
    ): Boolean

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
        resizeFilter: Int,
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
        resizeFilter: Int,
        quantScale: Float,
        quantZeroPoint: Int,
        output: ByteArray,
    ): Boolean

    private val ResizeFilter.nativeValue: Int
        get() = when (this) {
            ResizeFilter.NEAREST -> 0
            ResizeFilter.BILINEAR -> 1
        }

    private fun FloatArray.withMinSize(size: Int): FloatArray {
        return if (this.size >= size) this else FloatArray(size)
    }

    private fun ByteArray.withMinSize(size: Int): ByteArray {
        return if (this.size >= size) this else ByteArray(size)
    }

    private companion object {
        const val CHANNELS_RGB = 3
    }
}
