package com.sailens.runtime

import com.sailens.core.frame.ImageFrame
import kotlin.math.roundToInt

class ModelInputPreprocessor(
    private val config: ModelTensorConfig,
    private val inputQuantization: ModelInputQuantization,
    private val preferNativeYuvPreprocessing: Boolean,
    private val preprocessCache: InputPreprocessCache? = null,
    directFloatNchw: Boolean = false,
) : AutoCloseable {
    private val fallbackProcessor = OpenCVImageProcessor(config)
    private val nativeProcessor = NativeYuvInputPreprocessor(config, inputQuantization, directFloatNchw)
    private var fallbackFloatInput = FloatArray(0)

    fun sharedFloatInput(frame: ImageFrame): InputPreprocessCache.SharedFloatInput? = preprocessCache?.let {
        InputPreprocessCache.SharedFloatInput(
            it, InputPreprocessCache.Key.from(frame, frame.rotationDegrees, config), preferNativeYuvPreprocessing,
        )
    }

    fun preprocessFloat(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: FloatArray,
    ): InputPreprocessBackend = preprocessFloatWithMapping(frame, rotationDegrees, outputArray).backend

    fun preprocessFloatWithMapping(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: FloatArray,
        timing: InputPreprocessTiming? = null,
        preferredProducer: InputPreprocessCache.PreferredProducer? = null,
    ): PreparedImage {
        val cacheKey = InputPreprocessCache.Key.from(frame, rotationDegrees, config)
        val cache = preprocessCache
        cache?.awaitOrClaim(cacheKey, timing = timing, preferredProducer = preferredProducer) {
            cache.copyFloatWithMapping(cacheKey, outputArray, frame, timing)
        }?.let {
            return it
        }
        try {
            if (preferNativeYuvPreprocessing &&
                nativeProcessor.preprocessFloat(frame, rotationDegrees, outputArray, timing)
            ) {
                val backend = InputPreprocessBackend.NATIVE_YUV
                val mapping = nativeProcessor.imageMapping(frame, rotationDegrees)
                cache?.storeFloatInput(cacheKey, outputArray, backend, mapping, frame)
                return PreparedImage(backend, mapping)
            }
            val mapping = fallbackProcessor.preprocessWithMapping(frame, rotationDegrees, outputArray)
            val backend = InputPreprocessBackend.OPENCV_FALLBACK
            cache?.storeFloatInput(cacheKey, outputArray, backend, mapping, frame)
            return PreparedImage(backend, mapping)
        } finally {
            cache?.releaseClaim(cacheKey)
        }
    }

    /**
     * The native YUV path only: no shared cache, no OpenCV fallback. For a model whose geometry must
     * match the native letterbox exactly (the fallback truncates sizes and offsets to integers).
     *
     * @return false when the native path could not run (library missing, frame without YUV planes,
     *   wrong output size); [outputArray] is then not meaningful.
     */
    fun preprocessFloatNativeOnly(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: FloatArray,
    ): Boolean = nativeProcessor.preprocessFloat(frame, rotationDegrees, outputArray)

    fun preprocessInt8(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: ByteArray,
    ): InputPreprocessBackend = preprocessInt8WithMapping(frame, rotationDegrees, outputArray).backend

    fun preprocessInt8WithMapping(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: ByteArray,
        timing: InputPreprocessTiming? = null,
    ): PreparedImage {
        val cacheKey = InputPreprocessCache.Key.from(frame, rotationDegrees, config)
        val expectedSize = config.inputWidth * config.inputHeight * 3
        if (fallbackFloatInput.size != expectedSize) {
            fallbackFloatInput = FloatArray(expectedSize)
        }
        val cache = preprocessCache
        cache?.awaitOrClaim(cacheKey, timing = timing) {
            cache.copyInt8WithMapping(cacheKey, inputQuantization, outputArray, frame, timing)
                ?: cache.copyFloatWithMapping(cacheKey, fallbackFloatInput, frame, timing)?.let { prepared ->
                    quantizeFloatInput(fallbackFloatInput, outputArray)
                    val quantizedBackend = prepared.backend.asSharedQuantizedCacheHit()
                    cache.storeInt8Input(cacheKey, inputQuantization, outputArray, quantizedBackend, prepared.mapping, frame)
                    PreparedImage(quantizedBackend, prepared.mapping)
                }
        }?.let {
            return it
        }

        try {
            if (preferNativeYuvPreprocessing &&
                nativeProcessor.preprocessInt8(frame, rotationDegrees, outputArray, timing)
            ) {
                val backend = InputPreprocessBackend.NATIVE_YUV
                val mapping = nativeProcessor.imageMapping(frame, rotationDegrees)
                cache?.storeInt8Input(cacheKey, inputQuantization, outputArray, backend, mapping, frame)
                return PreparedImage(backend, mapping)
            }

            val mapping = fallbackProcessor.preprocessWithMapping(frame, rotationDegrees, fallbackFloatInput)
            quantizeFloatInput(fallbackFloatInput, outputArray)
            val backend = InputPreprocessBackend.OPENCV_FALLBACK
            cache?.storeFloatInput(cacheKey, fallbackFloatInput, backend, mapping, frame)
            cache?.storeInt8Input(cacheKey, inputQuantization, outputArray, backend, mapping, frame)
            return PreparedImage(backend, mapping)
        } finally {
            cache?.releaseClaim(cacheKey)
        }
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
    ): InputPreprocessBackend = preprocessUint8WithMapping(frame, rotationDegrees, outputArray).backend

    fun preprocessUint8WithMapping(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: ByteArray,
        timing: InputPreprocessTiming? = null,
    ): PreparedImage {
        val expectedSize = config.inputWidth * config.inputHeight * 3
        if (fallbackFloatInput.size != expectedSize) {
            fallbackFloatInput = FloatArray(expectedSize)
        }
        val prepared = preprocessFloatWithMapping(frame, rotationDegrees, fallbackFloatInput, timing)
        quantizeFloatInputUnsigned(fallbackFloatInput, outputArray)
        return prepared
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
