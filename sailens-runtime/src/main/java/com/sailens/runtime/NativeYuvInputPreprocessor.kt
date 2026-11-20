package com.sailens.runtime

import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat

internal class NativeYuvInputPreprocessor(
    private val config: ModelTensorConfig,
    private val inputQuantization: ModelInputQuantization,
) {
    private val mapping = FloatArray(7)

    fun imageMapping(frame: ImageFrame, rotationDegrees: Int): InputImageMapping {
        val sideways = rotationDegrees == 90 || rotationDegrees == 270
        val scale = mapping[0].toDouble()
        return InputImageMapping(
            if (sideways) frame.height else frame.width,
            if (sideways) frame.width else frame.height,
            config.inputWidth,
            config.inputHeight,
            InputImageMapping.Axis(scale, mapping[1].toDouble(), mapping[3].toInt(), mapping[4].toInt()),
            InputImageMapping.Axis(scale, mapping[2].toDouble(), mapping[5].toInt(), mapping[6].toInt()),
            rotationDegrees,
        )
    }
    private var nhwcFloatScratch = FloatArray(0)
    private var nhwcInt8Scratch = ByteArray(0)

    fun preprocessFloat(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: FloatArray,
        timing: InputPreprocessTiming? = null,
    ): Boolean {
        val yuv = frame.takeYuvDataOrNull() ?: return false
        if (!NativeRuntimeLibrary.isAvailable) return false
        val expectedSize = config.inputWidth * config.inputHeight * CHANNELS_RGB
        if (outputArray.size != expectedSize) return false

        val directNchw = timing?.requestDirectNchw == true &&
            config.inputLayout == ImageTensorLayout.NCHW && config.resizeFilter == ResizeFilter.NEAREST
        val nativeLayout = if (directNchw) ImageTensorLayout.NCHW else ImageTensorLayout.NHWC
        val transpose = config.inputLayout != nativeLayout
        val nativeOutput = if (transpose) {
            nhwcFloatScratch.withMinSize(expectedSize).also { nhwcFloatScratch = it }
        } else {
            outputArray
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
                outputLayout = nativeLayout.nativeValue,
                output = nativeOutput,
                mapping = mapping,
                timingNanos = timing?.beginNativeYuv(config.resizeFilter, nativeLayout),
            )
        }.getOrDefault(false)
        if (success && transpose) {
            val transposing = timing?.now() ?: 0L
            nativeOutput.copyHwcToNchw(
                output = outputArray,
                width = config.inputWidth,
                height = config.inputHeight,
                channels = CHANNELS_RGB,
            )
            timing?.transposedSince(transposing)
        }
        return success
    }

    fun preprocessInt8(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: ByteArray,
        timing: InputPreprocessTiming? = null,
    ): Boolean {
        val yuv = frame.takeYuvDataOrNull() ?: return false
        if (!NativeRuntimeLibrary.isAvailable) return false
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
                mapping = mapping,
                timingNanos = timing?.beginNativeYuv(config.resizeFilter),
            )
        }.getOrDefault(false)
        if (success && config.inputLayout == ImageTensorLayout.NCHW) {
            val transposing = timing?.now() ?: 0L
            nativeOutput.copyHwcToNchw(
                output = outputArray,
                width = config.inputWidth,
                height = config.inputHeight,
                channels = CHANNELS_RGB,
            )
            timing?.transposedSince(transposing)
        }
        return success
    }

    /** Native float -> int8 quantization (replaces the Kotlin per-element loop). Returns false when
     * the native lib is unavailable or sizes mismatch, so the caller can fall back. */
    fun quantizeFloatToInt8(input: FloatArray, output: ByteArray): Boolean {
        if (!NativeRuntimeLibrary.isAvailable) return false
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
        outputLayout: Int,
        output: FloatArray,
        mapping: FloatArray,
        timingNanos: LongArray?,
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
        mapping: FloatArray,
        timingNanos: LongArray?,
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
