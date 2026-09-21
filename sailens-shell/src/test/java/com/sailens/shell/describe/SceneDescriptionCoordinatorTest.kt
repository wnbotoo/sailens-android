package com.sailens.shell.describe

import com.sailens.camera.FrameLease
import com.sailens.camera.FrameSnapshotProvider
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.log.LogService
import com.sailens.describe.DescribeSceneUseCase
import com.sailens.shell.device.ScreenReaderAnnouncer
import com.sailens.shell.device.SpokenChannel
import com.sailens.vlm.SceneDescriber
import com.sailens.vlm.SceneDescription
import com.sailens.vlm.SceneDescriptionChunk
import com.sailens.vlm.SceneDescriptionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

/**
 * "Do not walk into that" outranks "what is that" — across screens.
 *
 * The bug these tests are built around: each screen had its own description session, so a
 * navigation warning cancelled the guidance screen's session, which was never the one running,
 * while the description the user was listening to on the Describe screen kept decoding and kept
 * talking after the warning. Here one side of each test plays the Describe screen (it starts and
 * cancels descriptions) and the other plays Guidance (it only ever preempts), and both hold the
 * same coordinator — as both ViewModels do through the shell's single binding.
 *
 * Everything runs on one thread, the way production runs on Main: that is what makes "a chunk
 * already dispatched when the warning lands" a real, reproducible ordering rather than a race.
 */
class SceneDescriptionCoordinatorTest {

    private val executor = Executors.newSingleThreadExecutor()
    private val main: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)

    private val chunks = Channel<SceneDescriptionChunk>(Channel.UNLIMITED)
    private val describer = ScriptedDescriber(chunks)
    private val voice = RecordingVoice()
    private val announcer = ScreenReaderAnnouncer()
    private val announced: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var failureSignals = 0
    private val channel = MutableStateFlow(SpokenChannel.OWN_TTS)

    private val coordinator = SceneDescriptionCoordinator(
        describeScene = DescribeSceneUseCase(describer, OneFrame, SilentLog),
        voice = voice,
        announcer = announcer,
        signalFailureWithoutSpeech = { failureSignals++ },
        channel = channel,
        scope = scope,
        logService = SilentLog,
    )

    init {
        scope.launch { announcer.announcements.collect { announced += it } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        main.close()
    }

    // ---- The cross-screen preemption --------------------------------------------------------

    @Test
    fun `a guidance warning stops the description the Describe screen started`() = runBlocking<Unit> {
        onMain { coordinator.describe(FAILURE) }
        chunks.send(delta("A tree is ahead. "))
        settle()
        assertEquals(listOf("A tree is ahead."), voice.spoken)

        onMain { coordinator.preemptForGuidance("obstacle ahead") }
        chunks.send(delta("It has a bench under it. "))
        chunks.send(completed("A tree is ahead. It has a bench under it."))
        settle()

        assertEquals("nothing may be spoken after the warning", listOf("A tree is ahead."), voice.spoken)
        assertTrue("what Describe had queued must be withdrawn", voice.withdrawals >= 1)
        assertFalse(coordinator.state.value.isDescribing)
    }

    @Test
    fun `a chunk already dispatched when the warning lands is not spoken`() = runBlocking<Unit> {
        onMain { coordinator.describe(FAILURE) }
        settle()

        // The chunk is handed over and the warning lands before the collector gets to run. Whether
        // it is stopped by prompt cancellation or by the token depends on how the engine hands
        // chunks over; this pins the behaviour either way. The token mechanism itself is covered
        // by SceneDescriptionSessionTest.
        onMain {
            chunks.trySend(delta("Steps lead down to the left. "))
            coordinator.preemptForGuidance("obstacle ahead")
        }
        settle()

        assertTrue("a pre-dispatched clause must not speak: ${voice.spoken}", voice.spoken.isEmpty())
    }

    @Test
    fun `the tail of a finished answer that is still being read out is withdrawn`() = runBlocking<Unit> {
        onMain { coordinator.describe(FAILURE) }
        chunks.send(completed("A bus stop, two metres ahead."))
        settle()
        assertFalse("decoding has finished", coordinator.state.value.isDescribing)
        assertTrue(voice.hasQueued)

        onMain { coordinator.preemptForGuidance("vehicle approaching") }

        assertFalse("the answer is still being read out and must stop for the warning", voice.hasQueued)
    }

    @Test
    fun `a preempted screen-reader answer is never announced`() = runBlocking<Unit> {
        channel.value = SpokenChannel.SCREEN_READER
        settle()
        onMain { coordinator.describe(FAILURE) }
        settle()

        onMain { coordinator.preemptForGuidance("obstacle ahead") }
        chunks.send(completed("A crossing with a signal."))
        settle()

        assertTrue("a late answer would talk over the warning: $announced", announced.isEmpty())
    }

    // ---- Describe's own lifecycle -------------------------------------------------------------

    @Test
    fun `cancelling from the Describe screen withdraws Describe's speech and stops decoding`() =
        runBlocking<Unit> {
            onMain { coordinator.describe(FAILURE) }
            chunks.send(delta("A shop entrance. "))
            settle()

            onMain { coordinator.cancel("user pressed stop") }
            chunks.send(delta("The door is open. "))
            settle()

            assertEquals(listOf("A shop entrance."), voice.spoken)
            assertFalse(voice.hasQueued)
            assertFalse(coordinator.state.value.isDescribing)
        }

    @Test
    fun `a second request while one is running is ignored rather than stacked`() = runBlocking<Unit> {
        onMain { assertTrue(coordinator.describe(FAILURE)) }
        chunks.send(delta("A shop entrance. "))
        settle()

        onMain { assertFalse(coordinator.describe(FAILURE)) }
        assertEquals(1, describer.requests)
        assertEquals(
            "an ignored request must not take the running answer off the air",
            listOf("A shop entrance."),
            voice.queued,
        )
    }

    @Test
    fun `a new request takes the rest of the previous answer off the air before it starts`() =
        runBlocking<Unit> {
            onMain { coordinator.describe(FAILURE) }
            chunks.send(completed("A bus stop, two metres ahead."))
            settle()
            assertFalse("decoding has finished", coordinator.state.value.isDescribing)
            assertEquals(
                "but the answer is still being read out",
                listOf("A bus stop, two metres ahead."),
                voice.queued,
            )

            onMain { assertTrue(coordinator.describe(FAILURE)) }
            assertTrue(
                "the old answer describes where the person was, not where they are: ${voice.queued}",
                voice.queued.isEmpty(),
            )

            chunks.send(delta("The bus has pulled in. "))
            chunks.send(completed("The bus has pulled in."))
            settle()

            assertEquals("only the new answer may be queued", listOf("The bus has pulled in."), voice.queued)
        }

    // ---- One answer, one channel --------------------------------------------------------------

    @Test
    fun `turning on a screen reader mid-answer cancels it instead of finishing in the wrong voice`() =
        runBlocking<Unit> {
            onMain { coordinator.describe(FAILURE) }
            chunks.send(delta("A pedestrian crossing. "))
            settle()

            channel.value = SpokenChannel.SCREEN_READER
            settle()
            chunks.send(delta("The light is red. "))
            chunks.send(completed("A pedestrian crossing. The light is red."))
            settle()

            assertEquals(listOf("A pedestrian crossing."), voice.spoken)
            assertTrue("the rest must not be handed to the screen reader either", announced.isEmpty())
            assertFalse(voice.hasQueued)
            assertFalse(coordinator.state.value.isDescribing)
        }

    @Test
    fun `turning speech off mid-answer cancels it`() = runBlocking<Unit> {
        onMain { coordinator.describe(FAILURE) }
        chunks.send(delta("A bicycle is parked here. "))
        settle()

        channel.value = SpokenChannel.SILENT
        settle()

        assertFalse(coordinator.state.value.isDescribing)
        assertFalse(voice.hasQueued)
    }

    @Test
    fun `the next request uses the new channel`() = runBlocking<Unit> {
        channel.value = SpokenChannel.SCREEN_READER
        settle()

        onMain { coordinator.describe(FAILURE) }
        chunks.send(delta("A kerb, then the road. "))
        chunks.send(completed("A kerb, then the road."))
        settle()

        assertTrue("a screen-reader answer is not streamed through the app's own voice", voice.spoken.isEmpty())
        assertEquals(listOf("A kerb, then the road."), announced)
    }

    // ---- Nobody is left in silence ------------------------------------------------------------

    @Test
    fun `an engine that never comes up is reported by the non-speech signal`() = runBlocking<Unit> {
        voice.ready = false

        onMain { coordinator.describe(FAILURE) }
        chunks.send(delta("A lamp post. "))
        chunks.send(completed("A lamp post."))
        settle()

        assertTrue(voice.spoken.isEmpty())
        assertEquals(1, failureSignals)
    }

    @Test
    fun `a failed request says so on the channel it was asked on`() = runBlocking<Unit> {
        onMain { coordinator.describe(FAILURE) }
        chunks.close(IllegalStateException("VLM crashed"))
        settle()

        assertEquals(listOf(FAILURE), voice.spoken)
        assertEquals(FAILURE, coordinator.state.value.errorMessage)
        assertFalse(coordinator.state.value.isDescribing)
    }

    // ---- Helpers --------------------------------------------------------------------------------

    /** Runs [block] on the coordinator's thread, as the ViewModels do on Main. */
    private suspend fun onMain(block: () -> Unit) = withContext(main) { block() }

    /** Lets everything already queued on the coordinator's thread run to completion. */
    private suspend fun settle() {
        repeat(4) { withContext(main) { } }
    }

    private fun delta(text: String) = SceneDescriptionChunk.Delta(text)

    private fun completed(text: String) = SceneDescriptionChunk.Completed(
        SceneDescription(text = text, backend = "test", latencyMs = 0, timeToFirstTokenMs = 0),
    )

    /**
     * Records what Describe asked to say, and what of it is still queued, unsaid. Nothing is ever
     * "read out" here, so an utterance stays queued until Describe withdraws it.
     */
    private class RecordingVoice : DescribeVoice {
        val spoken: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val queued: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var withdrawals = 0
        var ready = true
        val hasQueued: Boolean get() = queued.isNotEmpty()

        override suspend fun awaitReady(): Boolean = ready

        override fun speak(text: String) {
            spoken += text
            queued += text
        }

        override fun withdraw() {
            withdrawals++
            queued.clear()
        }
    }

    private class ScriptedDescriber(
        private val chunks: Channel<SceneDescriptionChunk>,
    ) : SceneDescriber {
        var requests = 0
        override val isAvailable: Boolean = true
        override val isReady: Boolean = true
        override suspend fun initialize() = Unit
        /**
         * Follows the real contract — zero or more deltas, then exactly one Completed, then the flow
         * ends — and reads the script without consuming it, so cancelling one description does not
         * close the channel the test is still writing to.
         */
        override fun describe(request: SceneDescriptionRequest): Flow<SceneDescriptionChunk> {
            requests++
            return flow {
                for (chunk in chunks) {
                    emit(chunk)
                    if (chunk is SceneDescriptionChunk.Completed) break
                }
            }
        }

        override suspend fun release() = Unit
    }

    private object OneFrame : FrameSnapshotProvider {
        private val frame = ImageFrame(
            width = 4,
            height = 4,
            pixelBytes = ByteArray(0),
            pixelFormat = ImagePixelFormat.YUV_420_888,
            rotationDegrees = 0,
            timestamp = 0L,
            sequenceNumber = 0L,
        )

        override fun currentFrame(maxAgeMs: Long): ImageFrame = frame
        override suspend fun awaitCurrentFrame(maxAgeMs: Long, timeoutMs: Long): ImageFrame = frame
        override fun openSnapshotLease(): FrameLease = object : FrameLease {
            override fun close() = Unit
        }
    }

    private object SilentLog : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private companion object {
        const val FAILURE = "Could not describe the scene."
    }
}
