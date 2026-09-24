package com.sailens.runtime

import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `a concurrent consumer of the same frame waits for the producer instead of converting again`() {
        val cache = InputPreprocessCache()
        val key = cacheKey(sequenceNumber = 21)
        val produced = floatArrayOf(0.4f, 0.5f, 0.6f)
        val claimed = java.util.concurrent.CountDownLatch(1)
        var conversions = 0

        val producer = Thread {
            val hit = cache.awaitOrClaim(key) { cache.copyFloatInput(key, FloatArray(3)) }
            assertNull(hit)
            claimed.countDown()
            try {
                Thread.sleep(20) // converting
                conversions++
                cache.storeFloatInput(key, produced, InputPreprocessBackend.NATIVE_YUV)
            } finally {
                cache.releaseClaim(key)
            }
        }
        producer.start()
        claimed.await()

        val output = FloatArray(3)
        val backend = cache.awaitOrClaim(key, waitMs = 1_000) { cache.copyFloatInput(key, output) }
        producer.join()

        assertEquals(1, conversions)
        assertEquals(InputPreprocessBackend.SHARED_NATIVE_YUV, backend)
        assertArrayEquals(produced, output, 0.0001f)
    }

    @Test
    fun `a waiter gives up after the bound and converts on its own`() {
        val cache = InputPreprocessCache()
        val key = cacheKey(sequenceNumber = 22)
        val claimed = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val stuck = Thread {
            cache.awaitOrClaim(key) { cache.copyFloatInput(key, FloatArray(3)) }
            claimed.countDown()
            done.await()
            cache.releaseClaim(key)
        }
        stuck.start()
        claimed.await()

        val started = System.nanoTime()
        val hit = cache.awaitOrClaim(key, waitMs = 30) { cache.copyFloatInput(key, FloatArray(3)) }
        val waitedMs = (System.nanoTime() - started) / 1_000_000

        done.countDown()
        stuck.join()
        assertNull(hit)
        assertTrue("waited $waitedMs ms", waitedMs in 25..500)
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
