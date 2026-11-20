package com.sailens.runtime

import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun `a timed hit separates monitor entry from copying and preserves the values`() {
        val cache = InputPreprocessCache()
        val key = cacheKey(sequenceNumber = 11)
        val values = floatArrayOf(0.25f, 0.5f, 0.75f)
        val output = FloatArray(values.size)
        var nanos = 0L
        val timing = InputPreprocessTiming { nanos.also { nanos += 10 } }
        cache.storeFloatInput(key, values, InputPreprocessBackend.NATIVE_YUV)

        val hit = cache.awaitOrClaim(key, timing = timing) {
            cache.copyFloatWithMapping(key, output, null, timing)
        }

        assertEquals(InputPreprocessBackend.SHARED_NATIVE_YUV, hit?.backend)
        assertEquals(10L, timing.cacheWaitNanos)
        assertEquals(10L, timing.cacheCopyNanos)
        assertArrayEquals(values, output, 0f)
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
    fun `reserving the float input changes the producer when detection reaches the cache first`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (preferSemantic in listOf(false, true)) {
                val cache = InputPreprocessCache()
                val key = cacheKey(sequenceNumber = 25)
                val share = InputPreprocessCache.SharedFloatInput(cache, key, preferNativeYuv = true)
                val preferred = if (preferSemantic) checkNotNull(share.reserveProducer()) else null
                val tensor = floatArrayOf(0.25f, 0.5f, 0.75f)
                val detInput = FloatArray(tensor.size)
                val semInput = FloatArray(tensor.size)
                val detLookedUp = CountDownLatch(1)
                val detectorProduced = executor.submit<Boolean> {
                    val hit = cache.awaitOrClaim(key, waitMs = 2_000) {
                        detLookedUp.countDown()
                        cache.copyFloatInput(key, detInput)
                    }
                    if (hit != null) return@submit false
                    try {
                        tensor.copyInto(detInput)
                        cache.storeFloatInput(key, detInput, InputPreprocessBackend.NATIVE_YUV)
                        true
                    } finally {
                        cache.releaseClaim(key)
                    }
                }
                assertTrue(detLookedUp.await(2, TimeUnit.SECONDS))
                preferred.use {
                    val hit = cache.awaitOrClaim(key, waitMs = 2_000, preferredProducer = preferred) {
                        cache.copyFloatInput(key, semInput)
                    }
                    assertEquals(preferSemantic, hit == null)
                    if (hit == null) {
                        try {
                            tensor.copyInto(semInput)
                            cache.storeFloatInput(key, semInput, InputPreprocessBackend.NATIVE_YUV)
                        } finally {
                            cache.releaseClaim(key)
                        }
                    }
                }
                assertEquals(!preferSemantic, detectorProduced.get(2, TimeUnit.SECONDS))
                assertArrayEquals(tensor, semInput, 0f)
                assertArrayEquals(tensor, detInput, 0f)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a reservation still allows fallback after the wait budget expires`() {
        val cache = InputPreprocessCache()
        val key = cacheKey(sequenceNumber = 26)
        val share = InputPreprocessCache.SharedFloatInput(cache, key, preferNativeYuv = true)
        checkNotNull(share.reserveProducer()).use {
            assertNull(cache.awaitOrClaim(key, waitMs = 0) { cache.copyFloatInput(key, FloatArray(3)) })
            cache.storeFloatInput(key, floatArrayOf(1f, 2f, 3f), InputPreprocessBackend.NATIVE_YUV)
            cache.releaseClaim(key)
            val output = FloatArray(3)
            val hit = cache.awaitOrClaim(key, preferredProducer = it) { cache.copyFloatInput(key, output) }
            assertEquals(InputPreprocessBackend.SHARED_NATIVE_YUV, hit)
            assertArrayEquals(floatArrayOf(1f, 2f, 3f), output, 0f)
        }
    }

    @Test
    fun `misses cache when frame identity changes`() {
        val cache = InputPreprocessCache()
        val key = cacheKey(sequenceNumber = 9)

        cache.storeFloatInput(key, floatArrayOf(0.1f, 0.2f, 0.3f), InputPreprocessBackend.NATIVE_YUV)

        assertNull(cache.copyFloatInput(cacheKey(sequenceNumber = 10), FloatArray(3)))
    }

    @Test
    fun `both cached tensor types retain mapping only for the same delivery`() {
        val cache = InputPreprocessCache()
        val frame = ImageFrame(1, 1, byteArrayOf(), ImagePixelFormat.RGBA_8888, 1000, 0, 9)
        val otherDelivery = frame.copy()
        val key = cacheKey(sequenceNumber = 9)
        val quant = ModelInputQuantization(1f / 255f, -128)
        val axis = InputImageMapping.Axis(1.0, 0.0, 0, 1)
        val mapping = InputImageMapping(1, 1, 1, 1, axis, axis, 0)
        cache.storeFloatInput(key, FloatArray(3), InputPreprocessBackend.NATIVE_YUV, mapping, frame)
        cache.storeInt8Input(key, quant, ByteArray(3), InputPreprocessBackend.SHARED_QUANTIZED_NATIVE_YUV, mapping, frame)

        assertEquals(mapping, cache.copyFloatWithMapping(key, FloatArray(3), frame)?.mapping)
        assertEquals(mapping, cache.copyInt8WithMapping(key, quant, ByteArray(3), frame)?.mapping)
        assertNull(cache.copyFloatWithMapping(key, FloatArray(3), otherDelivery)?.mapping)
        assertNull(cache.copyInt8WithMapping(key, quant, ByteArray(3), otherDelivery)?.mapping)
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
