package com.sailens.vlm

import android.content.Context
import android.content.ContextWrapper
import com.google.ai.edge.litert.Accelerator
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Initialization is idempotent under concurrency: callers that race into [LiteRtVlmEngine.initialize]
 * share one runtime, and none of them closes the runtime another has just built.
 */
class LiteRtVlmEngineLifecycleTest {

    @Test
    fun `concurrent initialize builds one runtime and closes none`() = runBlocking {
        val factory = CountingFactory()
        val engine = LiteRtVlmEngine(
            context = ContextWrapper(null),
            config = VlmModelConfig(modelPath = VlmModelConfig.DEFAULT_MODEL_ASSET),
            runtimeFactory = factory,
            logService = null,
        )

        // Every caller is released at once, so all of them get past the fast-path check before the
        // first one has finished building.
        val start = CountDownLatch(1)
        withTimeout(TIMEOUT_MS) {
            (1..8).map {
                async(Dispatchers.Default) {
                    start.await()
                    engine.initialize()
                }
            }.also { start.countDown() }.awaitAll()
        }

        assertEquals("one runtime for all callers", 1, factory.created.get())
        assertEquals("nobody tore down the runtime another caller built", 0, factory.closed.get())
        assertTrue(engine.isReady)

        val chunks = mutableListOf<SceneDescriptionChunk>()
        withTimeout(TIMEOUT_MS) {
            engine.describe(SceneDescriptionRequest(frame())).collect { chunks += it }
        }
        assertTrue(chunks.last() is SceneDescriptionChunk.Completed)
    }

    @Test
    fun `describe after release fails instead of decoding on a closed runtime`() = runBlocking {
        val factory = CountingFactory()
        val engine = LiteRtVlmEngine(
            context = ContextWrapper(null),
            config = VlmModelConfig(modelPath = VlmModelConfig.DEFAULT_MODEL_ASSET),
            runtimeFactory = factory,
            logService = null,
        )
        engine.initialize()
        engine.release()

        val failure = runCatching {
            withTimeout(TIMEOUT_MS) { engine.describe(SceneDescriptionRequest(frame())).collect { } }
        }.exceptionOrNull()

        assertTrue("expected a not-initialized failure, got $failure", failure is IllegalStateException)
        assertEquals(0, factory.generatedOnClosed.get())
    }

    private fun frame() = ImageFrame(
        width = 4,
        height = 4,
        pixelBytes = ByteArray(0),
        pixelFormat = ImagePixelFormat.YUV_420_888,
        rotationDegrees = 0,
        timestamp = 0L,
        sequenceNumber = 0L,
    )

    private class CountingFactory : VlmRuntimeFactory {
        val created = AtomicInteger(0)
        val closed = AtomicInteger(0)
        val generatedOnClosed = AtomicInteger(0)

        override fun isAvailable(context: Context): Boolean = true

        override fun create(context: Context, config: VlmModelConfig, accelerator: Accelerator): VlmRuntime {
            created.incrementAndGet()
            // Slow enough that the other callers pile up behind the first build.
            Thread.sleep(50)
            return object : VlmRuntime {
                @Volatile
                private var isClosed = false

                override fun generate(
                    prompt: String,
                    image: ImageFrame?,
                    shouldStop: () -> Boolean,
                    onToken: (String) -> Unit,
                ): String {
                    if (isClosed) generatedOnClosed.incrementAndGet()
                    onToken("ok")
                    return "ok"
                }

                override fun close() {
                    isClosed = true
                    closed.incrementAndGet()
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
