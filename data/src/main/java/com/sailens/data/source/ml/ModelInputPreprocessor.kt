package com.sailens.data.source.ml

import com.sailens.core.frame.ImageFrame
import kotlin.math.roundToInt

internal class ModelInputPreprocessor(
    private val config: ModelTensorConfig,
    private val inputQuantization: ModelInputQuantization,
    private val preferNativeYuvPreprocessing: Boolean,
    private val preprocessCache: InputPreprocessCache? = null,
) : AutoCloseable {
    private val fallbackProcessor = OpenCVImageProcessor(config)
    private val nativeProcessor = NativeYuvInputPreprocessor(config, inputQuantization)
    private var fallbackFloatInput = FloatArray(0)

    fun preprocessFloat(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: FloatArray,
    ): InputPreprocessBackend {
        val cacheKey = InputPreprocessCache.Key.from(frame, rotationDegrees, config)
        preprocessCache?.copyFloatInput(cacheKey, outputArray)?.let { backend ->
            return backend
        }

        if (preferNativeYuvPreprocessing &&
            nativeProcessor.preprocessFloat(frame, rotationDegrees, outputArray)
        ) {
            val backend = InputPreprocessBackend.NATIVE_YUV
            preprocessCache?.storeFloatInput(cacheKey, outputArray, backend)
            return backend
        }
        fallbackProcessor.preprocess(frame, rotationDegrees, outputArray)
        val backend = InputPreprocessBackend.OPENCV_FALLBACK
        preprocessCache?.storeFloatInput(cacheKey, outputArray, backend)
        return backend
    }

    fun preprocessInt8(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: ByteArray,
    ): InputPreprocessBackend {
        val cacheKey = InputPreprocessCache.Key.from(frame, rotationDegrees, config)
        preprocessCache?.copyInt8Input(cacheKey, inputQuantization, outputArray)?.let { backend ->
            return backend
        }

        val expectedSize = config.inputWidth * config.inputHeight * 3
        if (fallbackFloatInput.size != expectedSize) {
            fallbackFloatInput = FloatArray(expectedSize)
        }
        preprocessCache?.copyFloatInput(cacheKey, fallbackFloatInput)?.let { backend ->
            quantizeFloatInput(fallbackFloatInput, outputArray)
            val quantizedBackend = backend.asSharedQuantizedCacheHit()
            preprocessCache.storeInt8Input(cacheKey, inputQuantization, outputArray, quantizedBackend)
            return quantizedBackend
        }

        if (preferNativeYuvPreprocessing &&
            nativeProcessor.preprocessInt8(frame, rotationDegrees, outputArray)
        ) {
            val backend = InputPreprocessBackend.NATIVE_YUV
            preprocessCache?.storeInt8Input(cacheKey, inputQuantization, outputArray, backend)
            return backend
        }

        fallbackProcessor.preprocess(frame, rotationDegrees, fallbackFloatInput)
        quantizeFloatInput(fallbackFloatInput, outputArray)
        val backend = InputPreprocessBackend.OPENCV_FALLBACK
        preprocessCache?.storeFloatInput(cacheKey, fallbackFloatInput, backend)
        preprocessCache?.storeInt8Input(cacheKey, inputQuantization, outputArray, backend)
        return backend
    }

    /**
     * Produces asymmetric UINT8 input (byte pattern 0..255) for models whose input tensor is
     * quantized as UINT8 — e.g. Qualcomm AI Hub exports with zero-point 0, scale 1/255.
     *
     * B0 keeps this on the Kotlin quantizer only: it reuses the shared float `[0,1]` preprocess
     * (native/OpenCV + cache) and then quantizes with an unsigned clamp. The native int8 quantizer
     * ([NativeYuvInputPreprocessor.quantizeFloatToInt8]) is deliberately not used here because it
     * clamps to the signed int8 range; a native UINT8 fast path is a later optimization.
     */
    fun preprocessUint8(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: ByteArray,
    ): InputPreprocessBackend {
        val expectedSize = config.inputWidth * config.inputHeight * 3
        if (fallbackFloatInput.size != expectedSize) {
            fallbackFloatInput = FloatArray(expectedSize)
        }
        val backend = preprocessFloat(frame, rotationDegrees, fallbackFloatInput)
        quantizeFloatInputUnsigned(fallbackFloatInput, outputArray)
        return backend
    }

    fun postprocess(scores: FloatArray, resultMask: IntArray) {
        fallbackProcessor.postprocess(scores, resultMask)
    }

    private fun quantizeFloatInputUnsigned(
        input: FloatArray,
        output: ByteArray,
    ) {
        require(input.size == output.size) {
            "Input float size ${input.size} does not match uint8 output size ${output.size}"
        }
        val scale = inputQuantization.scale
        val zeroPoint = inputQuantization.zeroPoint
        require(scale > 0f) { "Input quantization scale must be > 0, got $scale" }

        for (index in input.indices) {
            val quantized = (input[index] / scale + zeroPoint).roundToInt()
            // Store the low 8 bits of the unsigned [0,255] value: 128..255 land in the negative
            // half of the signed Byte but preserve the correct bit pattern for the UINT8 tensor.
            output[index] = quantized.coerceIn(UINT8_MIN, UINT8_MAX).toByte()
        }
    }

    private fun quantizeFloatInput(
        input: FloatArray,
        output: ByteArray,
    ) {
        require(input.size == output.size) {
            "Input float size ${input.size} does not match int8 output size ${output.size}"
        }
        if (nativeProcessor.quantizeFloatToInt8(input, output)) return

        // Kotlin fallback when the native lib is unavailable.
        val scale = inputQuantization.scale
        val zeroPoint = inputQuantization.zeroPoint
        require(scale > 0f) { "Input quantization scale must be > 0, got $scale" }

        for (index in input.indices) {
            val quantized = (input[index] / scale + zeroPoint).roundToInt()
            output[index] = quantized.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
        }
    }

    override fun close() {
        fallbackProcessor.close()
    }

    private companion object {
        private const val UINT8_MIN = 0
        private const val UINT8_MAX = 255
    }
}
