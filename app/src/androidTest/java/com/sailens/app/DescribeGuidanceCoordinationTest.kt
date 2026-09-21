package com.sailens.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sailens.camera.FrameLease
import com.sailens.camera.FrameSnapshotProvider
import com.sailens.camera.FrameSource
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.frame.Yuv420FrameData
import com.sailens.core.frame.YuvPlaneData
import com.sailens.guidance.model.perception.ObstacleModelOutput
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.repository.ObstacleProvider
import com.sailens.guidance.repository.PerceptionRepository
import com.sailens.output.SpeechEngineState
import com.sailens.output.SpeechManager
import com.sailens.output.SpeechOwner
import com.sailens.shell.describe.DescribeViewModel
import com.sailens.shell.describe.DescribeVoice
import com.sailens.shell.describe.SceneDescriptionCoordinator
import com.sailens.shell.describe.SpeechManagerDescribeVoice
import com.sailens.shell.device.SharedEngineOwner
import com.sailens.shell.guidance.screen.SceneAnalysisViewModel
import com.sailens.vlm.SceneDescriber
import com.sailens.vlm.SceneDescription
import com.sailens.vlm.SceneDescriptionChunk
import com.sailens.vlm.SceneDescriptionRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Guidance and Describe, both on screen at once, on a real device.
 *
 * The bug behind this suite was invisible to single-screen tests: each screen had its own
 * description session, so a navigation warning cancelled the guidance screen's session — never the
 * one running — while the description the user was actually listening to kept decoding and kept
 * talking after the warning. And each screen released the shared speech engine on its way out, so
 * closing Describe could leave Guidance with no voice.
 *
 * One Describe-only case lives here too, because it means nothing without a real engine: an answer
 * that has finished decoding is still being read out when the person asks again.
 *
 * Everything here is the production object graph: the app's own [appModule], both real ViewModels,
 * the real coordinator, the real engine owner and the real [SpeechManager] speaking through the
 * device's TTS. Only the inputs are replaced — camera frames, perception and the VLM — so the test
 * needs no camera, no model weights and no GPU. The guidance event is real: uniform frames make the
 * real FrameQualityAnalyzer report an obstructed camera, which the real pipeline turns into a
 * warning.
 *
 * Needs a device: `./gradlew.bat :app:connectedDebugAndroidTest`. Leave TalkBack off; with it on,
 * speech goes to the screen reader and there is nothing for the app's own engine to prove.
 */
@RunWith(AndroidJUnit4::class)
class DescribeGuidanceCoordinationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    private val describer = StreamingDescriber()
    private val camera = SyntheticCamera()
    private lateinit var voice: RecordingVoice
    private val stores = mutableListOf<ViewModelStore>()

    private val inputs = module {
        single<SceneDescriber> { describer }
        single<FrameSnapshotProvider> { OneFrameSnapshots }
        single<FrameSource> { camera }
        single<PerceptionRepository> { SidewalkAhead }
        single<ObstacleProvider>(named("realtimeObstacleProvider")) { NoObstacles }
        single<DescribeVoice> {
            RecordingVoice(SpeechManagerDescribeVoice(speechManager = get())).also { voice = it }
        }
    }

    private val koin: Koin get() = GlobalContext.get()

    @Before
    fun setUp() {
        // A fresh production graph per test, so no instance survives from one test into the next.
        stopKoin()
        startKoin {
            androidContext(app)
            allowOverride(true)
            modules(appModule, inputs)
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { stores.forEach { it.clear() } }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun aGuidanceWarningStopsTheDescriptionTheDescribeScreenIsRunning() {
        val describeScreen = viewModel<DescribeViewModel>()
        val guidanceScreen = viewModel<SceneAnalysisViewModel>()
        val speech = koin.get<SpeechManager>()
        awaitTrue("the speech engine to come up", 10_000) { speech.isReady }

        instrumentation.runOnMainSync { describeScreen.describe("Could not describe the scene.") }
        awaitTrue("Describe to start speaking", 5_000) { voice.spoken().isNotEmpty() }

        // Start guidance with the lens covered: the real pipeline reports an obstructed camera.
        camera.obstructed = true
        val guidanceStartedAt = System.nanoTime()
        instrumentation.runOnMainSync { guidanceScreen.toggleAnalysis() }
        awaitTrue("Guidance to announce a warning", 10_000) {
            guidanceScreen.uiState.value.lastAnnouncedEvent != null
        }
        // Not the first withdrawal of the test: starting the description withdrew too.
        val preemptedAt = voice.firstWithdrawalAfter(guidanceStartedAt)
        assertNotNull("the warning must withdraw Describe's speech", preemptedAt)

        // The fake VLM offers a new clause every 150 ms for as long as it is allowed to run.
        Thread.sleep(1_500)

        assertTrue("generation must stop, not just the speech", describer.decodeStopped)
        assertEquals(
            "no Describe clause may be spoken after the warning",
            emptyList<String>(),
            voice.spokenAfter(preemptedAt!!),
        )
        assertFalse("nothing Describe queued may still be waiting", voice.real.hasQueuedSpeech)
        assertFalse(koin.get<SceneDescriptionCoordinator>().state.value.isDescribing)
        assertTrue("Guidance keeps its voice", speech.isReady)
    }

    @Test
    fun closingDescribeLeavesGuidanceItsSpeechEngine() {
        viewModel<SceneAnalysisViewModel>()
        val describeStore = ViewModelStore()
        viewModel<DescribeViewModel>(describeStore)
        val speech = koin.get<SpeechManager>()
        val owner = koin.get<SharedEngineOwner>()
        awaitTrue("the speech engine to come up", 10_000) { speech.isReady }
        assertEquals(2, owner.activeLeases)

        instrumentation.runOnMainSync { describeStore.clear() }
        instrumentation.waitForIdleSync()

        assertEquals(1, owner.activeLeases)
        assertEquals("closing Describe must not release Guidance's engine", SpeechEngineState.READY, speech.state.value)

        val guidanceProbe = SpeechOwner("guidance-probe")
        instrumentation.runOnMainSync { speech.speakSystemNotice("Guidance can still speak.", guidanceProbe) }
        instrumentation.waitForIdleSync()
        assertTrue("the engine must still accept Guidance's speech", speech.hasQueued(guidanceProbe))
        instrumentation.runOnMainSync { speech.withdraw(guidanceProbe) }
    }

    @Test
    fun cancellingDescribeDoesNotCutOffGuidanceSpeech() {
        viewModel<SceneAnalysisViewModel>()
        val describeScreen = viewModel<DescribeViewModel>()
        val speech = koin.get<SpeechManager>()
        awaitTrue("the speech engine to come up", 10_000) { speech.isReady }

        // A warning is being read out, and Describe's clauses queue up behind it.
        val guidance = SpeechOwner("guidance")
        instrumentation.runOnMainSync {
            speech.speakSystemNotice(
                "Obstacle ahead. Stop and step to the left, then continue slowly along the kerb.",
                guidance,
            )
            describeScreen.describe("Could not describe the scene.")
        }
        awaitTrue("Describe to queue behind the warning", 5_000) { voice.real.hasQueuedSpeech }

        instrumentation.runOnMainSync { describeScreen.cancel() }
        instrumentation.waitForIdleSync()

        assertFalse("Describe's queued clauses are withdrawn", voice.real.hasQueuedSpeech)
        assertTrue("the warning survives Describe being cancelled", speech.hasQueued(guidance))
        instrumentation.runOnMainSync { speech.withdraw(guidance) }
    }

    @Test
    fun askingAgainTakesThePreviousAnswerOffTheAirFirst() {
        val describeScreen = viewModel<DescribeViewModel>()
        val speech = koin.get<SpeechManager>()
        val coordinator = koin.get<SceneDescriptionCoordinator>()
        awaitTrue("the speech engine to come up", 10_000) { speech.isReady }

        // The first answer decodes at once and takes seconds to read out, so Describe is offered
        // again while it is still playing.
        describer.answers += FIRST_ANSWER
        instrumentation.runOnMainSync { describeScreen.describe("Could not describe the scene.") }
        awaitTrue("the first answer to finish decoding", 5_000) {
            coordinator.state.value.let { it.isComplete && !it.isDescribing }
        }
        assertTrue("the first answer must still be being read out", voice.real.hasQueuedSpeech)

        val askedAgainAt = System.nanoTime()
        instrumentation.runOnMainSync { describeScreen.describe("Could not describe the scene.") }

        val withdrawnAt = voice.firstWithdrawalAfter(askedAgainAt)
        assertNotNull("asking again must take the first answer back", withdrawnAt)
        assertEquals(
            "the engine must hold nothing of the first answer once it is taken back",
            false,
            voice.queuedAfterLastWithdrawal,
        )
        awaitTrue("the second answer to start speaking", 5_000) {
            voice.spokenAfter(askedAgainAt).isNotEmpty()
        }
        assertEquals(
            "the second answer may queue nothing before the first is gone",
            voice.spokenAfter(askedAgainAt),
            voice.spokenAfter(withdrawnAt!!),
        )
        instrumentation.runOnMainSync { describeScreen.cancel() }
    }

    private inline fun <reified T : ViewModel> viewModel(store: ViewModelStore = ViewModelStore()): T {
        stores += store
        var viewModel: T? = null
        instrumentation.runOnMainSync {
            val created = koin.get<T>()
            viewModel = ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <V : ViewModel> create(modelClass: Class<V>): V = created as V
                },
            )[T::class.java]
        }
        return viewModel!!
    }

    private fun awaitTrue(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    /** Records what Describe asked to say and when it took its speech back, then passes both on. */
    private class RecordingVoice(val real: SpeechManagerDescribeVoice) : DescribeVoice {
        private val events = CopyOnWriteArrayList<Pair<String, Long>>()

        /** Whether the engine still held any of Describe's speech right after the last withdrawal. */
        @Volatile
        var queuedAfterLastWithdrawal: Boolean? = null
            private set

        override suspend fun awaitReady(): Boolean = real.awaitReady()

        override fun speak(text: String) {
            events += "speak:$text" to System.nanoTime()
            real.speak(text)
        }

        override fun withdraw() {
            events += "withdraw" to System.nanoTime()
            real.withdraw()
            // Withdrawals arrive on Main, where SpeechManager applies them before returning.
            queuedAfterLastWithdrawal = real.hasQueuedSpeech
        }

        fun spoken(): List<String> = events.filter { it.first.startsWith("speak:") }.map { it.first }

        fun firstWithdrawalAfter(timeNs: Long): Long? =
            events.firstOrNull { it.first == "withdraw" && it.second > timeNs }?.second

        fun spokenAfter(timeNs: Long): List<String> =
            events.filter { it.first.startsWith("speak:") && it.second > timeNs }.map { it.first }
    }

    /**
     * A VLM that keeps decoding until it is stopped, and says so when it is — unless [answers] holds
     * one for this request, which it gives at once, whole, and ends.
     */
    private class StreamingDescriber : SceneDescriber {
        @Volatile
        var decodeStopped = false
        val answers = ConcurrentLinkedQueue<String>()
        override val isAvailable: Boolean = true
        override val isReady: Boolean = true
        override suspend fun initialize() = Unit
        override fun describe(request: SceneDescriptionRequest): Flow<SceneDescriptionChunk> = flow {
            answers.poll()?.let { answer ->
                emit(SceneDescriptionChunk.Delta(answer))
                emit(
                    SceneDescriptionChunk.Completed(
                        SceneDescription(text = answer, backend = "test", latencyMs = 0, timeToFirstTokenMs = 0),
                    ),
                )
                return@flow
            }
            try {
                var clause = 0
                while (true) {
                    emit(SceneDescriptionChunk.Delta("Clause ${++clause} of the description. "))
                    delay(150)
                }
            } finally {
                decodeStopped = true
            }
        }

        override suspend fun release() = Unit
    }

    /**
     * Camera frames the real pipeline can read. Uniform luma while [obstructed], which the real
     * FrameQualityAnalyzer classifies as a covered lens; noise otherwise.
     */
    private class SyntheticCamera : FrameSource {
        @Volatile
        var obstructed = false

        override val frames: Flow<ImageFrame> = flow {
            var sequence = 0L
            while (true) {
                emit(yuvFrame(sequence++, uniform = obstructed))
                delay(66)
            }
        }
    }

    private object OneFrameSnapshots : FrameSnapshotProvider {
        override fun currentFrame(maxAgeMs: Long): ImageFrame = yuvFrame(0, uniform = false)
        override suspend fun awaitCurrentFrame(maxAgeMs: Long, timeoutMs: Long): ImageFrame =
            yuvFrame(0, uniform = false)

        override fun openSnapshotLease(): FrameLease = object : FrameLease {
            override fun close() = Unit
        }
    }

    /** Perception without a model: an open sidewalk ahead, so perception itself raises nothing. */
    private object SidewalkAhead : PerceptionRepository {
        private const val CITYSCAPES_SIDEWALK = 1
        override val isInitialized: Boolean = true
        override suspend fun initialize() = Unit
        override suspend fun segment(frame: ImageFrame): Result<SegmentationOutput> = Result.success(
            SegmentationOutput(
                mask = SegmentationMask(16, 16, IntArray(16 * 16) { CITYSCAPES_SIDEWALK }),
                preprocessTimeMs = 0,
                inferenceTimeMs = 0,
                postprocessTimeMs = 0,
            ),
        )

        override suspend fun release() = Unit
    }

    private object NoObstacles : ObstacleProvider {
        override val isInitialized: Boolean = true
        override suspend fun initialize() = Unit
        override suspend fun detect(frame: ImageFrame): ObstacleModelOutput = ObstacleModelOutput(emptyList())
        override fun release() = Unit
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 48

        /** Several seconds of speech, so it is still playing long after it has been decoded. */
        const val FIRST_ANSWER = "A bus stop is two metres ahead. A bench stands under its shelter. " +
            "Behind it, bicycles are parked along the kerb. Further on, the pavement narrows beside a hedge."

        fun yuvFrame(sequence: Long, uniform: Boolean): ImageFrame {
            val random = Random(sequence)
            val luma = ByteArray(WIDTH * HEIGHT) { if (uniform) 110.toByte() else random.nextInt(256).toByte() }
            val chroma = ByteArray(WIDTH * HEIGHT / 4) { 128.toByte() }
            return ImageFrame(
                width = WIDTH,
                height = HEIGHT,
                pixelBytes = ByteArray(0),
                pixelFormat = ImagePixelFormat.YUV_420_888,
                rotationDegrees = 0,
                timestamp = sequence,
                sequenceNumber = sequence,
                yuvData = Yuv420FrameData(
                    y = YuvPlaneData(bytes = luma, rowStride = WIDTH, pixelStride = 1),
                    u = YuvPlaneData(bytes = chroma, rowStride = WIDTH / 2, pixelStride = 1),
                    v = YuvPlaneData(bytes = chroma.copyOf(), rowStride = WIDTH / 2, pixelStride = 1),
                ),
            )
        }
    }
}
