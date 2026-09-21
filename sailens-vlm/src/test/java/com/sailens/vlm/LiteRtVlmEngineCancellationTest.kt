package com.sailens.vlm

import android.content.Context
import android.content.ContextWrapper
import com.google.ai.edge.litert.Accelerator
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cancellation has to reach the decode, not just the collector.
 *
 * An autoregressive decode runs for seconds. When Guidance preempts a description, a decode that
 * keeps running holds the accelerator and delays the next request — and the shell has no way to
 * make it stop other than this path. [VlmRuntime.shouldStop] is the contract; this proves the
 * engine actually wires cancellation into it.
 */
class LiteRtVlmEngineCancellationTest {

    @Test
    fun `cancelling the collection stops the decode`() = runBlocking {
        val runtime = LoopingRuntime()
        val engine = engineWith(runtime)
        engine.initialize()

        val collector = launch(Dispatchers.Default) {
            engine.describe(SceneDescriptionRequest(frame())).collect { }
        }
        withTimeout(TIMEOUT_MS) { runtime.decoding.await() }

        collector.cancelAndJoin()

        withTimeout(TIMEOUT_MS) { runtime.finished.await() }
        assertTrue("the decode must observe shouldStop, not run to completion", runtime.stoppedEarly.get())
        assertFalse("a stopped decode must not keep emitting tokens", runtime.emittedAfterStop.get())
    }

    @Test
    fun `a decode that is left alone runs to completion`() = runBlocking {
        val runtime = LoopingRuntime(tokenBudget = 3)
        val engine = engineWith(runtime)
        engine.initialize()

        val chunks = engine.describe(SceneDescriptionRequest(frame())).toListWithTimeout()

        assertFalse(runtime.stoppedEarly.get())
        assertTrue(
            "the terminal chunk is part of the contract",
            chunks.last() is SceneDescriptionChunk.Completed,
        )
    }

    private suspend fun kotlinx.coroutines.flow.Flow<SceneDescriptionChunk>.toListWithTimeout():
        List<SceneDescriptionChunk> {
        val collected = mutableListOf<SceneDescriptionChunk>()
        withTimeout(TIMEOUT_MS) { collect { collected += it } }
        return collected
    }

    private fun engineWith(runtime: VlmRuntime) = LiteRtVlmEngine(
        context = ContextWrapper(null),
        config = VlmModelConfig(modelPath = VlmModelConfig.DEFAULT_MODEL_ASSET),
        runtimeFactory = FakeRuntimeFactory(runtime),
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

    /**
     * Stands in for a real autoregressive decode: it keeps producing tokens until asked to stop,
     * which is exactly the shape that makes an un-cancellable generation a problem.
     */
    private class LoopingRuntime(private val tokenBudget: Int = Int.MAX_VALUE) : VlmRuntime {
        val decoding = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val stoppedEarly = AtomicBoolean(false)
        val emittedAfterStop = AtomicBoolean(false)
        private val tokens = AtomicInteger(0)

        override fun generate(
            prompt: String,
            image: ImageFrame?,
            shouldStop: () -> Boolean,
            onToken: (String) -> Unit,
        ): String {
            val text = StringBuilder()
            try {
                while (tokens.get() < tokenBudget) {
                    if (shouldStop()) {
                        stoppedEarly.set(true)
                        break
                    }
                    tokens.incrementAndGet()
                    text.append("token ")
                    onToken("token ")
                    decoding.complete(Unit)
                    if (stoppedEarly.get()) emittedAfterStop.set(true)
                }
            } finally {
                decoding.complete(Unit)
                finished.complete(Unit)
            }
            return text.toString()
        }

        override fun close() = Unit
    }

    private class FakeRuntimeFactory(private val runtime: VlmRuntime) : VlmRuntimeFactory {
        override fun isAvailable(context: Context): Boolean = true

        override fun create(
            context: Context,
            config: VlmModelConfig,
            accelerator: Accelerator,
        ): VlmRuntime = runtime
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
