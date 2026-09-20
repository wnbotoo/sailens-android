package com.sailens.data.source.ml

import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputPreprocessCacheTest {
    @Test
    fun `copies float input for the same frame and tensor config`() {
        val cache = InputPreprocessCache()
        val key = cacheKey(sequenceNumber = 7)
        val cachedInput = floatArrayOf(0.1f, 0.2f, 0.3f)
        val output = FloatArray(cachedInput.size)

        cache.storeFloatInput(key, cachedInput, InputPreprocessBackend.NATIVE_YUV)

        val backend = cache.copyFloatInput(key, output)

        assertEquals(InputPreprocessBackend.SHARED_NATIVE_YUV, backend)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), output, 0.0001f)
    }

    @Test
    fun `keeps quantized cache source visible in backend trace`() {
        val cache = InputPreprocessCache()
        val key = cacheKey(sequenceNumber = 8)
        val quantization = ModelInputQuantization(scale = 1f / 255f, zeroPoint = -128)
        val cachedInput = byteArrayOf(-128, 0, 127)
        val output = ByteArray(cachedInput.size)

        cache.storeInt8Input(
            key = key,
            quantization = quantization,
            inputArray = cachedInput,
            backend = InputPreprocessBackend.SHARED_QUANTIZED_NATIVE_YUV,
        )

        val backend = cache.copyInt8Input(key, quantization, output)

        assertEquals(InputPreprocessBackend.SHARED_QUANTIZED_NATIVE_YUV, backend)
        assertArrayEquals(byteArrayOf(-128, 0, 127), output)
    }

    @Test
    fun `misses cache when frame identity changes`() {
        val cache = InputPreprocessCache()
        val key = cacheKey(sequenceNumber = 9)

        cache.storeFloatInput(key, floatArrayOf(0.1f, 0.2f, 0.3f), InputPreprocessBackend.NATIVE_YUV)

        assertNull(cache.copyFloatInput(cacheKey(sequenceNumber = 10), FloatArray(3)))
    }

    private fun cacheKey(sequenceNumber: Long): InputPreprocessCache.Key {
        val config = ModelTensorConfig(
            inputWidth = 1,
            inputHeight = 1,
            outputWidth = 1,
            outputHeight = 1,
            outputChannels = 3,
            mean = Triple(0f, 0f, 0f),
            std = Triple(1f, 1f, 1f),
        )
        val frame = ImageFrame(
            width = 1,
            height = 1,
            pixelBytes = byteArrayOf(),
            pixelFormat = ImagePixelFormat.RGBA_8888,
            timestamp = 1000,
            rotationDegrees = 0,
            sequenceNumber = sequenceNumber,
        )
        return InputPreprocessCache.Key.from(frame, rotationDegrees = 0, config = config)
    }
}
