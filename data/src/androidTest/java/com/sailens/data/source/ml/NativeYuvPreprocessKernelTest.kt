package com.sailens.data.source.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.domain.model.perception.ImageFrame
import com.sailens.domain.model.perception.ImagePixelFormat
import com.sailens.domain.model.perception.Yuv420FrameData
import com.sailens.domain.model.perception.YuvPlaneData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Layer B of the native verification plan (docs/architecture.md §12.2), preprocessing kernels:
 * `nativePreprocessYuvToFloat`, `nativePreprocessYuvToInt8` and `nativeQuantizeFloatToInt8`.
 *
 * These three have no production Kotlin twin to diff against -- the fallback is OpenCV, whose
 * resampling does not agree value-for-value -- so the oracle is [referenceNhwcFloat], a direct
 * re-derivation of the documented algorithm: nearest-neighbour letterbox resize, BT.601 YUV to
 * RGB, `/255`, then `(v - mean) / std`. That does not prove the algorithm is the right one; it
 * pins plane strides, chroma subsampling, rotation and letterbox geometry, which is what the
 * module split can break.
 *
 * Needs a physical arm64 device: `./gradlew.bat :data:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class NativeYuvPreprocessKernelTest {

    @Before
    fun requireNativeLibrary() {
        assertTrue(
            "libsailens_ml.so did not load: ${NativeMlLibrary.loadError}",
            NativeMlLibrary.isAvailable,
        )
    }

    @Test
    fun yuvToFloatMatchesTheReferenceNearestPath() {
        val frame = syntheticFrame(width = 64, height = 64, uvPixelStride = 2)
        val config = tensorConfig(inputWidth = 64, inputHeight = 64)
        val preprocessor = NativeYuvInputPreprocessor(config, ModelInputQuantization())
        val output = FloatArray(64 * 64 * 3)

        assertTrue(preprocessor.preprocessFloat(frame, rotationDegrees = 0, outputArray = output))

        assertFloatsEqual(referenceNhwcFloat(frame, rotationDegrees = 0, config = config), output)
    }

    @Test
    fun yuvToFloatLetterboxesAndRotates() {
        // Non-square source into a square tensor, so the kernel has to pad; 90 degrees is the
        // rotation the portrait camera actually delivers.
        val frame = syntheticFrame(width = 64, height = 32, uvPixelStride = 1)
        val config = tensorConfig(inputWidth = 48, inputHeight = 48)
        val preprocessor = NativeYuvInputPreprocessor(config, ModelInputQuantization())
        val output = FloatArray(48 * 48 * 3)

        assertTrue(preprocessor.preprocessFloat(frame, rotationDegrees = 90, outputArray = output))

        val expected = referenceNhwcFloat(frame, rotationDegrees = 90, config = config)
        assertFloatsEqual(expected, output)

        // A 32x64 rotated frame into 48x48 leaves left/right padding, so column 0 must be the
        // pad colour rather than image content.
        val padValue = (0f - config.mean.first) / config.std.first
        assertEquals("column 0 should be letterbox padding", padValue, output[0], TOLERANCE)
    }

    @Test
    fun yuvToInt8EqualsQuantizingTheFloatOutput() {
        val frame = syntheticFrame(width = 64, height = 64, uvPixelStride = 2)
        val config = tensorConfig(inputWidth = 64, inputHeight = 64)
        val quantization = ModelInputQuantization(scale = 1f / 255f, zeroPoint = -128)
        val preprocessor = NativeYuvInputPreprocessor(config, quantization)

        val floatOutput = FloatArray(64 * 64 * 3)
        assertTrue(preprocessor.preprocessFloat(frame, 0, floatOutput))

        val int8Output = ByteArray(64 * 64 * 3)
        assertTrue(preprocessor.preprocessInt8(frame, 0, int8Output))

        // Both kernels quantise with the same expression on the same float32 intermediate, so the
        // fused path must agree byte for byte with quantising the float path's result.
        val quantizedFloatOutput = ByteArray(floatOutput.size)
        assertTrue(preprocessor.quantizeFloatToInt8(floatOutput, quantizedFloatOutput))

        assertArrayEquals(quantizedFloatOutput, int8Output)
    }

    @Test
    fun quantizeFloatToInt8RoundsAndSaturates() {
        val quantization = ModelInputQuantization(scale = 1f / 255f, zeroPoint = -128)
        val preprocessor = NativeYuvInputPreprocessor(
            tensorConfig(inputWidth = 8, inputHeight = 8),
            quantization,
        )
        // Deliberately away from .5 ties: the kernel rounds half away from zero (lround) and
        // Kotlin's roundToInt rounds half up, so a tie would compare two different rules.
        val input = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 1f, 2f, -1f)
        val output = ByteArray(input.size)

        assertTrue(preprocessor.quantizeFloatToInt8(input, output))

        val expected = input.map { value ->
            val quantized = Math.round(value / quantization.scale + quantization.zeroPoint)
            quantized.coerceIn(-128, 127).toByte()
        }.toByteArray()
        assertArrayEquals(expected, output)
        assertEquals("2.0 saturates at the top of the int8 range", 127.toByte(), output[5])
        assertEquals("-1.0 saturates at the bottom of the int8 range", (-128).toByte(), output[6])
    }

    @Test
    fun quantizeFloatToInt8RejectsMismatchedSizes() {
        val preprocessor = NativeYuvInputPreprocessor(
            tensorConfig(inputWidth = 8, inputHeight = 8),
            ModelInputQuantization(),
        )

        assertTrue(!preprocessor.quantizeFloatToInt8(FloatArray(4), ByteArray(3)))
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private fun tensorConfig(inputWidth: Int, inputHeight: Int) = ModelTensorConfig(
        inputWidth = inputWidth,
        inputHeight = inputHeight,
        inputLayout = ImageTensorLayout.NHWC,
        outputWidth = inputWidth,
        outputHeight = inputHeight,
        outputChannels = 19,
        mean = Triple(0.1f, 0.2f, 0.3f),
        std = Triple(0.5f, 0.4f, 0.6f),
        resizeFilter = ResizeFilter.NEAREST,
    )

    /**
     * A YUV_420_888 frame whose every plane value is a function of its coordinates, so a wrong
     * stride or a swapped plane shows up as a wrong colour rather than as plausible noise.
     */
    private fun syntheticFrame(width: Int, height: Int, uvPixelStride: Int): ImageFrame {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val yRowStride = width + 16 // padded row stride, as real camera buffers have
        val uvRowStride = chromaWidth * uvPixelStride + 8

        val yBytes = ByteArray(yRowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                yBytes[y * yRowStride + x] = (16 + (x * 3 + y * 5) % 220).toByte()
            }
        }

        val uBytes = ByteArray(uvRowStride * chromaHeight)
        val vBytes = ByteArray(uvRowStride * chromaHeight)
        for (y in 0 until chromaHeight) {
            for (x in 0 until chromaWidth) {
                val index = y * uvRowStride + x * uvPixelStride
                uBytes[index] = (40 + (x * 7) % 180).toByte()
                vBytes[index] = (60 + (y * 11) % 180).toByte()
            }
        }

        return ImageFrame(
            width = width,
            height = height,
            pixelBytes = ByteArray(0),
            pixelFormat = ImagePixelFormat.YUV_420_888,
            timestamp = 1L,
            rotationDegrees = 0,
            sequenceNumber = 1L,
            yuvData = Yuv420FrameData(
                y = YuvPlaneData(yBytes, rowStride = yRowStride, pixelStride = 1),
                u = YuvPlaneData(uBytes, rowStride = uvRowStride, pixelStride = uvPixelStride),
                v = YuvPlaneData(vBytes, rowStride = uvRowStride, pixelStride = uvPixelStride),
            ),
        )
    }

    private fun assertFloatsEqual(expected: FloatArray, actual: FloatArray) {
        assertEquals("output length", expected.size, actual.size)
        for (index in expected.indices) {
            assertEquals("value at index $index", expected[index], actual[index], TOLERANCE)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reference implementation
    // ---------------------------------------------------------------------------------------

    private class ReferencePlane(
        val bytes: ByteArray,
        val rowStride: Int,
        val pixelStride: Int,
        val width: Int,
        val height: Int,
        val defaultValue: Int,
    ) {
        fun at(x: Int, y: Int): Int {
            if (x < 0 || y < 0 || x >= width || y >= height) return defaultValue
            val index = y * rowStride + x * pixelStride
            if (index < 0 || index >= bytes.size) return defaultValue
            return bytes[index].toInt() and 0xFF
        }

        fun nearest(x: Float, y: Float): Float {
            if (width <= 0 || height <= 0) return defaultValue.toFloat()
            // Truncation, matching the kernel's static_cast<int>(v + 0.5f).
            val nearestX = (x + 0.5f).toInt().coerceIn(0, width - 1)
            val nearestY = (y + 0.5f).toInt().coerceIn(0, height - 1)
            return at(nearestX, nearestY).toFloat()
        }
    }

    private fun referenceNhwcFloat(
        frame: ImageFrame,
        rotationDegrees: Int,
        config: ModelTensorConfig,
    ): FloatArray {
        val yuv = requireNotNull(frame.yuvData)
        val sourceWidth = frame.width
        val sourceHeight = frame.height
        val chromaWidth = (sourceWidth + 1) / 2
        val chromaHeight = (sourceHeight + 1) / 2

        val yPlane = ReferencePlane(
            yuv.y.bytes, yuv.y.rowStride, yuv.y.pixelStride, sourceWidth, sourceHeight, 16,
        )
        val uPlane = ReferencePlane(
            yuv.u.bytes, yuv.u.rowStride, yuv.u.pixelStride, chromaWidth, chromaHeight, 128,
        )
        val vPlane = ReferencePlane(
            yuv.v.bytes, yuv.v.rowStride, yuv.v.pixelStride, chromaWidth, chromaHeight, 128,
        )

        val targetWidth = config.inputWidth
        val targetHeight = config.inputHeight
        val (meanR, meanG, meanB) = config.mean
        val (stdR, stdG, stdB) = config.std

        val rotation = ((rotationDegrees % 360) + 360) % 360
        val rotated = rotation == 90 || rotation == 270
        val rotatedWidth = if (rotated) sourceHeight else sourceWidth
        val rotatedHeight = if (rotated) sourceWidth else sourceHeight
        val scale = min(
            targetWidth / rotatedWidth.toFloat(),
            targetHeight / rotatedHeight.toFloat(),
        )
        val resizedWidth = rotatedWidth * scale
        val resizedHeight = rotatedHeight * scale
        val padX = (targetWidth - resizedWidth) * 0.5f
        val padY = (targetHeight - resizedHeight) * 0.5f

        val activeStartX = ceil(padX).toInt().coerceIn(0, targetWidth)
        val activeEndX = ceil(padX + resizedWidth).toInt().coerceIn(activeStartX, targetWidth)
        val activeStartY = ceil(padY).toInt().coerceIn(0, targetHeight)
        val activeEndY = ceil(padY + resizedHeight).toInt().coerceIn(activeStartY, targetHeight)

        val padR = (0f - meanR) / stdR
        val padG = (0f - meanG) / stdG
        val padB = (0f - meanB) / stdB

        val output = FloatArray(targetWidth * targetHeight * 3)
        for (y in 0 until targetHeight) {
            var outIndex = y * targetWidth * 3
            val rotatedY = (y.toFloat() - padY + 0.5f) / scale - 0.5f

            for (x in 0 until targetWidth) {
                val inActiveArea = y in activeStartY until activeEndY &&
                    x in activeStartX until activeEndX
                if (!inActiveArea) {
                    output[outIndex++] = padR
                    output[outIndex++] = padG
                    output[outIndex++] = padB
                    continue
                }

                val rotatedX = (x.toFloat() - padX + 0.5f) / scale - 0.5f
                val sourceX: Float
                val sourceY: Float
                when (rotation) {
                    90 -> {
                        sourceX = rotatedY
                        sourceY = (sourceHeight - 1).toFloat() - rotatedX
                    }

                    180 -> {
                        sourceX = (sourceWidth - 1).toFloat() - rotatedX
                        sourceY = (sourceHeight - 1).toFloat() - rotatedY
                    }

                    270 -> {
                        sourceX = (sourceWidth - 1).toFloat() - rotatedY
                        sourceY = rotatedX
                    }

                    else -> {
                        sourceX = rotatedX
                        sourceY = rotatedY
                    }
                }

                val yValue = yPlane.nearest(sourceX, sourceY)
                val uValue = uPlane.nearest(sourceX * 0.5f, sourceY * 0.5f)
                val vValue = vPlane.nearest(sourceX * 0.5f, sourceY * 0.5f)

                val c = max(yValue - 16f, 0f)
                val d = uValue - 128f
                val e = vValue - 128f
                val r = (1.164f * c + 1.596f * e).coerceIn(0f, 255f)
                val g = (1.164f * c - 0.392f * d - 0.813f * e).coerceIn(0f, 255f)
                val b = (1.164f * c + 2.017f * d).coerceIn(0f, 255f)

                output[outIndex++] = (r / 255f - meanR) / stdR
                output[outIndex++] = (g / 255f - meanG) / stdG
                output[outIndex++] = (b / 255f - meanB) / stdB
            }
        }
        return output
    }

    private companion object {
        // The kernel is built with -ffast-math, so it may contract or reassociate the colour
        // conversion. Compare on the scale of the normalised output, not bit for bit.
        const val TOLERANCE = 1e-3f
    }
}
