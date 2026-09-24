package com.sailens.vlm

import android.content.Context
import android.content.ContextWrapper
import com.google.ai.edge.litert.Accelerator
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The engine's lifecycle is serialised on its own thread: callers that race into
 * [LiteRtVlmEngine.initialize] share one runtime, a release racing an initialize never leaves a
 * caller holding a closed engine, and a decode never runs on a closed runtime.
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
    fun `initialize racing a release that is already running still ends ready`() = runBlocking {
        // The shell releases asynchronously when the last screen lets go; a new screen can ask for
        // the engine while that release is mid-way. Before the fix, initialize() saw isReady == true
        // outside the engine thread, returned at once, and the queued release then closed the
        // runtime under a caller that had just been told it was ready.
        val factory = CountingFactory(blockFirstClose = true)
        val engine = engine(factory)
        engine.initialize()

        val release = async(Dispatchers.Default) { engine.release() }
        assertTrue("release must be inside close()", factory.closeEntered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val initialize = async(Dispatchers.Default) { engine.initialize() }
        // Let initialize() reach its readiness check while the release is still inside close().
        delay(200)
        factory.releaseClose.countDown()
        withTimeout(TIMEOUT_MS) {
            release.await()
            initialize.await()
        }

        assertTrue("initialize returned, so the engine must be ready", engine.isReady)
        assertEquals("a fresh runtime replaced the released one", 2, factory.created.get())
        val chunks = mutableListOf<SceneDescriptionChunk>()
        withTimeout(TIMEOUT_MS) { engine.describe(SceneDescriptionRequest(frame())).collect { chunks += it } }
        assertTrue(chunks.last() is SceneDescriptionChunk.Completed)
        assertEquals(0, factory.generatedOnClosed.get())
    }

    @Test
    fun `describe after a release loads a fresh runtime instead of decoding on a closed one`() = runBlocking {
        val factory = CountingFactory()
        val engine = engine(factory)
        engine.initialize()
        engine.release()

        val chunks = mutableListOf<SceneDescriptionChunk>()
        withTimeout(TIMEOUT_MS) { engine.describe(SceneDescriptionRequest(frame())).collect { chunks += it } }

        assertTrue(chunks.last() is SceneDescriptionChunk.Completed)
        assertEquals(2, factory.created.get())
        assertEquals(0, factory.generatedOnClosed.get())
    }

    private fun engine(factory: VlmRuntimeFactory) = LiteRtVlmEngine(
        context = ContextWrapper(null),
        config = VlmModelConfig(modelPath = VlmModelConfig.DEFAULT_MODEL_ASSET),
        runtimeFactory = factory,
        logService = null,
    )

    private fun frame() = ImageFrame(
        width = 4,
        height = 4,
        pixelBytes = ByteArray(0),
        pixelFormat = ImagePixelFormat.YUV_420_888,
        rotationDegrees = 0,
        timestamp = 0L,
        sequenceNumber = 0L,
    )

    private class CountingFactory(private val blockFirstClose: Boolean = false) : VlmRuntimeFactory {
        val created = AtomicInteger(0)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
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
                    if (blockFirstClose && closed.get() == 0) {
                        closeEntered.countDown()
                        releaseClose.await()
                    }
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
