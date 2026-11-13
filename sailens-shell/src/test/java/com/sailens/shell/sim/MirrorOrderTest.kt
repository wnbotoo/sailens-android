package com.sailens.shell.sim

import com.sailens.core.log.LogService
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneResult
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.traceLevel
import com.sailens.output.Announcement
import com.sailens.output.SpeechEngineState
import com.sailens.output.UtteranceOutcome
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.shell.device.NavigationClaim
import com.sailens.shell.device.SharedEngineOwner
import com.sailens.shell.guidance.safety.GuidanceSystemAlerts
import com.sailens.shell.guidance.screen.GuidanceOutputWiring
import com.sailens.shell.guidance.settings.GuidanceFeedbackSettings
import com.sailens.shell.safetynotice.SAFETY_NOTICE_VERSION
import com.sailens.shell.safetynotice.SafetyNoticeStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The view model's mirror, entry by entry, against `SceneAnalysisViewModel.kt` itself: every seam the mirror reaches
 * (the system alerts, the output wiring, speech, the motor, the run sequencer's stop and reset, the shared engines)
 * is a fake that writes each call into one record, and each test drives one entry and compares the record with the
 * calls the view model makes there, in its order. The expected lists are written by hand from the view model's
 * source, each call with its line there, not from the mirror. A UI flag a call happens before or after is part of
 * the call where the order of the two is the point.
 */
class MirrorOrderTest {

    private val calls = mutableListOf<String>()
    private var now = 0L
    private var failure: Throwable? = null

    private class Delay(val atMs: Long, val resume: () -> Unit) {
        var cancelled = false
    }

    private val delays = mutableListOf<Delay>()
    private val dispatcher = SimDispatcher {}.also { dispatcher ->
        dispatcher.scheduleDelay = { delayMs, resume ->
            val delay = Delay(now + delayMs, resume)
            delays += delay
            ({ delay.cancelled = true })
        }
    }

    /** What the analysis flow gives: each a result, or an error it throws. */
    private val upstream = Channel<Any>(Channel.UNLIMITED)
    private val engineState = MutableStateFlow(SpeechEngineState.READY)
    private val settings = MutableStateFlow(GuidanceFeedbackSettings())
    private val screenReader = MutableStateFlow(false)
    private var runs = 0
    private lateinit var mirror: ViewModelMirror

    private val ui get() = mirror.uiState.value

    private val speech = object : SpeechDevice {
        override val state = engineState
        override val isReady = true
        override fun initialize() { calls += "speech.initialize" }
        override fun setSpeechRate(rate: Float) { calls += "speech.setSpeechRate" }
        override fun speak(announcement: Announcement, onSpoken: (() -> Unit)?, onOutcome: ((UtteranceOutcome) -> Unit)?, atMs: Long?): Boolean {
            calls += "speech.speak"
            return true
        }
        override fun settleFrame(): Long = now
        override fun speakSystemNotice(text: String, priority: Int?) { calls += "speech.speakSystemNotice" }
        override fun stop() { calls += "speech.stop" }
        override fun release() { calls += "speech.release" }
    }

    private val haptics = object : HapticDevice {
        override fun trigger(event: SceneEvent): Boolean = true.also { calls += "haptics.trigger" }
        override fun triggerOrQueue(event: SceneEvent, onQueuedResult: (played: Boolean) -> Unit) = NavigationClaim.STARTED.also { calls += "haptics.triggerOrQueue" }
        override fun playOrReserve(haptic: GuidanceHaptic, speechPriority: Int, amplitude: Int, onPlayed: () -> Unit) { calls += "haptics.playOrReserve" }
        override fun cancelReservation() { calls += "haptics.cancelReservation" }
        override fun play(haptic: GuidanceHaptic): Boolean = true.also { calls += "haptics.play" }
        override fun cancelNavigation() { calls += "haptics.cancelNavigation" }
        override fun dropQueuedNavigation() { calls += "haptics.dropQueuedNavigation" }
        override fun cancel() { calls += "haptics.cancel" }
    }

    private val alerts = object : MirrorAlerts {
        override fun onStartRequested() { calls += "alerts.onStartRequested loading=${ui.isLoading}" }
        override fun onStallWatchStarted() { calls += "alerts.onStallWatchStarted" }
        override fun onStallWatchStopped() { calls += "alerts.onStallWatchStopped" }
        override fun onFlowStarted() { calls += "alerts.onFlowStarted running=${ui.isRunning}" }
        override fun onResult(baseline: BaselineQualification, sequenceNumber: Long, receivedAtMs: Long?) { calls += "alerts.onResult ${baseline.traceLevel}" }
        override fun onFlowFailed(error: Throwable) { calls += "alerts.onFlowFailed ${error.message}" }
        override fun onStopped() { calls += "alerts.onStopped running=${ui.isRunning} scope=${if (mirror.viewModelScope.isActive) "active" else "cancelled"}" }
        override fun endOfSession() { calls += "alerts.endOfSession" }
        override fun onLifecycleStop(noticeText: String) { calls += "alerts.onLifecycleStop $noticeText" }
        override fun onLifecycleStart() { calls += "alerts.onLifecycleStart" }
        override fun onOutput(engineState: SpeechEngineState, speechEnabled: Boolean, screenReaderActive: Boolean, hapticsEnabled: Boolean) {
            calls += "alerts.onOutput $engineState speech=$speechEnabled screenReader=$screenReaderActive haptics=$hapticsEnabled"
        }
        override fun poll() { calls += "alerts.poll" }
        override val holdsOrdinaryPrompts: Boolean
            get() = false.also { calls += "alerts.holdsOrdinaryPrompts" }
    }

    private val output = object : MirrorOutput {
        override fun stop(beforeOutputsStop: () -> Unit) {
            calls += "output.stop"
            beforeOutputsStop()
            // GuidanceOutputWiring.stop: speech, then the motor, after the block.
            calls += "output.stop: speech and motor"
        }
        override fun onFrame(events: List<SceneEvent>, sequenceNumber: Long, visionFaults: VisionFaults, holdOrdinary: Boolean) { calls += "output.onFrame $sequenceNumber hold=$holdOrdinary" }
        override fun holdBegan() { calls += "output.holdBegan" }
        override fun settingsChanged() { calls += "output.settingsChanged" }
        override fun reset() { calls += "output.reset" }
    }

    private val seams = object : MirrorSeams {
        override fun alerts(build: () -> GuidanceSystemAlerts) = alerts
        override fun output(build: () -> GuidanceOutputWiring) = output
    }

    private val observer = object : MirrorObserver {
        override fun runRequested(): Int = ++runs
        override fun blockStarted(run: Int, job: Job) { calls += "run begins" }
        override fun resultArrived(run: Int, result: SceneResult) = Unit
        override fun resultHandled(run: Int, sequenceNumber: Long) = Unit
        override fun stopIssued(resetJob: Job) { calls += "runs.stop" }
    }

    private val silent = object : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private fun build(safetyNoticeStore: SafetyNoticeStore = ConfirmedSafetyNotice) {
        mirror = ViewModelMirror(
            frames = { emptyFlow() },
            depthFrames = { emptyFlow() },
            analysis = { _, _, _, _ ->
                calls += "analysis"
                flow {
                    for (item in upstream) {
                        if (item is Throwable) throw item
                        emit(item as SceneResult)
                    }
                }
            },
            resetProcessors = { calls += "reset" },
            releaseModels = { calls += "release models" },
            description = object : DescriptionDevice {
                override val holdsTheFloor = false
                override fun preemptForGuidance(reason: String) = Unit
            },
            announceToScreenReader = {},
            sharedEngineOwner = SharedEngineOwner(releaseEngines = { calls += "engines released" }),
            haptics = haptics,
            speech = speech,
            logger = silent,
            record = object : SessionRecord {
                override fun recordSafetyState(trace: SafetyStateTrace) = Unit
                override fun recordGuidanceAlert(trace: GuidanceAlertTrace) = Unit
                override fun recordPromptOutcome(trace: PromptOutcomeTrace) = Unit
                override fun ui(what: String, detail: String?) = Unit
            },
            settings = settings,
            screenReaderActive = screenReader,
            textResolver = SimAnnouncements::text,
            announcements = SimAnnouncements::announcementFor,
            cooldown = object : CooldownReceipts {
                override fun confirm(event: SceneEvent) = Unit
                override fun revoke(event: SceneEvent, reason: String) = Unit
                override fun reportCut(event: SceneEvent) = Unit
                override fun reportHeardAfterCut(event: SceneEvent) = Unit
                override fun accepted(event: SceneEvent) = false
            },
            noticeText = { it.name },
            detFirst = DetFirstConfig(),
            clock = { now },
            context = dispatcher + CoroutineExceptionHandler { _, error -> failure = error },
            observer = observer,
            seams = seams,
            safetyNoticeStore = safetyNoticeStore,
        )
    }

    /** A safety notice store that starts empty, as on a fresh install. */
    private class NoticeStore : SafetyNoticeStore {
        var confirmed: Int? = null
        override fun confirmedVersion(): Int? = confirmed
        override fun recordConfirmed(version: Int) { confirmed = version }
    }

    /** Runs the shell's thread until nothing is queued, then hands back what was called since the last time. */
    private fun drained(): List<String> {
        dispatcher.drain()
        failure?.let { throw it }
        return taken()
    }

    private fun taken(): List<String> = calls.toList().also { calls.clear() }

    /** Moves time to [atMs], resuming every delay due by then, in order. */
    private fun advanceTo(atMs: Long) {
        while (true) {
            val due = delays.filter { !it.cancelled && it.atMs <= atMs }.minByOrNull { it.atMs } ?: break
            delays.remove(due)
            now = due.atMs
            due.resume()
            dispatcher.drain()
        }
        now = atMs
    }

    private fun built() {
        build()
        drained()
    }

    private fun started() {
        built()
        mirror.toggleAnalysis()
        drained()
    }

    @After
    fun noFailure() {
        failure?.let { throw it }
    }

    @Test
    fun `building the screen`() {
        build()
        assertEquals(
            listOf(
                "speech.setSpeechRate", // :267
                "speech.initialize", // :268-270, :309
            ),
            taken(),
        )
        assertEquals(
            listOf(
                // :271-275, the settings' first value: applyFeedbackSettings (:384-408)
                "speech.setSpeechRate", // :393
                "alerts.onOutput IDLE speech=true screenReader=false haptics=true", // :405, :331-334
                "output.settingsChanged", // :407
                // :276-280, the screen reader's first value: applyScreenReaderMode returns at :318
                // :281-291, the engine's first state
                "alerts.onOutput READY speech=true screenReader=false haptics=true", // :289
            ),
            drained(),
        )
    }

    @Test
    fun `start`() {
        built()
        mirror.toggleAnalysis()
        assertEquals(listOf("alerts.onStartRequested loading=false"), taken()) // :349, before isLoading
        assertEquals(
            listOf(
                "run begins", // runs.start's block (:446)
                "alerts.onStallWatchStarted", // :458, :513
                "analysis", // :461
                "alerts.onFlowStarted running=false", // :468, before isRunning
            ),
            drained(),
        )
        assertEquals("the flow's start is in the UI state after it", true, ui.isRunning)
    }

    @Test
    fun `a start before the safety notice is confirmed shows the notice and nothing else`() {
        val store = NoticeStore()
        build(store)
        drained()
        mirror.toggleAnalysis() // :354
        assertEquals(emptyList<String>(), drained())
        assertTrue(mirror.isSafetyNoticeShown.value)
        assertFalse(ui.isRunning)
        assertEquals(null, store.confirmed)
    }

    @Test
    fun `leaving the safety notice records nothing and starts nothing, and the next start shows it again`() {
        val store = NoticeStore()
        build(store)
        drained()
        mirror.toggleAnalysis()
        mirror.dismissSafetyNotice() // :368-370
        assertEquals(emptyList<String>(), drained())
        assertFalse(mirror.isSafetyNoticeShown.value)
        assertFalse(ui.isRunning)
        assertEquals(null, store.confirmed)

        mirror.toggleAnalysis()
        assertEquals(emptyList<String>(), drained())
        assertTrue("not confirmed, so shown again", mirror.isSafetyNoticeShown.value)
    }

    @Test
    fun `the safety notice's button records it and starts Guidance as the start control does`() {
        val store = NoticeStore()
        build(store)
        drained()
        mirror.toggleAnalysis()
        mirror.confirmSafetyNotice() // :363-365, then the start control's own decision
        assertEquals(SAFETY_NOTICE_VERSION, store.confirmed)
        assertFalse(mirror.isSafetyNoticeShown.value)
        assertEquals(listOf("alerts.onStartRequested loading=false"), taken())
        assertEquals(
            listOf("run begins", "alerts.onStallWatchStarted", "analysis", "alerts.onFlowStarted running=false"),
            drained(),
        )
        assertTrue(ui.isRunning)

        mirror.confirmSafetyNotice()
        assertEquals("a second press after the notice closed does nothing", emptyList<String>(), drained())
        assertTrue(ui.isRunning)

        mirror.toggleAnalysis()
        drained()
        assertFalse(ui.isRunning)
        mirror.toggleAnalysis()
        assertFalse("confirmed, so the next start does not show it", mirror.isSafetyNoticeShown.value)
        assertEquals(listOf("alerts.onStartRequested loading=false"), taken())
    }

    @Test
    fun `a result reaches the alerts before the output`() {
        started()
        val event = SceneEvent(timestamp = 0, category = EventCategory.OBSTACLE, priority = EventPriority.HIGH, messageKey = "event_obstacle_center_person", expiresAt = 10_000, dedupeKey = "d")
        upstream.trySend(SceneResult(sequenceNumber = 7, passableMask = null, events = listOf(event)))
        assertEquals(
            listOf(
                "alerts.onResult qualified", // :475
                "alerts.holdsOrdinaryPrompts", // :476, how this result's prompts and card go
                "output.onFrame 7 hold=false", // :505
            ),
            drained(),
        )
        assertEquals("the frame's events are in the UI state (:483-490)", listOf(event), ui.lastEvents)
    }

    @Test
    fun `a failure of the flow`() {
        started()
        upstream.trySend(IllegalStateException("the pipeline gave up"))
        assertEquals(listOf("alerts.onFlowFailed the pipeline gave up"), drained()) // :470-471
    }

    @Test
    fun `the stall loop polls every 500 ms until it is cancelled`() {
        started()
        advanceTo(1_000)
        assertEquals(listOf("alerts.poll", "alerts.poll"), taken()) // :514-519
        mirror.toggleAnalysis()
        drained()
        advanceTo(3_000)
        assertEquals("cancelled with the stop", emptyList<String>(), drained())
    }

    @Test
    fun `the engine's state`() {
        built()
        engineState.value = SpeechEngineState.UNAVAILABLE
        assertEquals(listOf("alerts.onOutput UNAVAILABLE speech=true screenReader=false haptics=true"), drained()) // :282-290
        engineState.value = SpeechEngineState.UNAVAILABLE
        assertEquals("the same state again is no change", emptyList<String>(), drained())
    }

    @Test
    fun `the screen reader`() {
        built()
        screenReader.value = true
        assertEquals(
            listOf(
                "speech.stop", // :322
                "alerts.onOutput READY speech=true screenReader=true haptics=true", // :327
            ),
            drained(),
        )
        screenReader.value = false
        assertEquals(
            listOf(
                "speech.initialize", // :323-324, :309
                "alerts.onOutput READY speech=true screenReader=false haptics=true", // :327
            ),
            drained(),
        )
    }

    @Test
    fun `the feedback settings`() {
        built()
        settings.update { it.copy(speechEnabled = false) }
        assertEquals(
            listOf(
                "speech.setSpeechRate", // :393
                "speech.stop", // :395-396
                "alerts.onOutput READY speech=false screenReader=false haptics=true", // :405
                "output.settingsChanged", // :407
            ),
            drained(),
        )
        settings.update { it.copy(hapticsEnabled = false) }
        assertEquals(
            listOf(
                "speech.setSpeechRate", // :393
                "haptics.cancelNavigation", // :401-403
                "alerts.onOutput READY speech=false screenReader=false haptics=false", // :405
                "output.settingsChanged", // :407
            ),
            drained(),
        )
        settings.update { it.copy(speechEnabled = true) }
        assertEquals(
            listOf(
                "speech.setSpeechRate", // :393
                "speech.initialize", // :397-398, :309
                "alerts.onOutput READY speech=true screenReader=false haptics=false", // :405
                "output.settingsChanged", // :407
            ),
            drained(),
        )
    }

    @Test
    fun `the lifecycle`() {
        started()
        mirror.onGuidanceInterrupted("interrupted")
        assertEquals(listOf("alerts.onLifecycleStop interrupted"), taken()) // :537
        mirror.acknowledgeInterruption()
        assertEquals(listOf("alerts.onLifecycleStart"), taken()) // :542
    }

    @Test
    fun `stop`() {
        started()
        mirror.toggleAnalysis()
        assertEquals(
            listOf(
                "alerts.onStallWatchStopped", // :572, :523-524
                "output.stop", // :574
                "runs.stop", // :575
                "output.stop: speech and motor", // GuidanceOutputWiring.stop, after the block
                "alerts.onStopped running=true scope=active", // :581, before the UI state
                "alerts.endOfSession", // :583
            ),
            taken(),
        )
        assertFalse("stopped in the UI state", ui.isRunning)
        assertEquals("the reset, once the run has finished", listOf("reset"), drained())
    }

    @Test
    fun `leaving the screen`() {
        started()
        mirror.onCleared()
        assertEquals(
            listOf(
                // viewModelScope is cancelled before onCleared runs (:662)
                "alerts.onStopped running=true scope=cancelled", // :587
                "alerts.endOfSession", // :588
                "alerts.onStallWatchStopped", // :589, :523-524
                "output.reset", // :590
                "haptics.cancel", // :593
                "runs.stop", // :594, :664
                "engines released", // :597, the last lease
            ),
            taken(),
        )
        assertEquals("the reset, then the models' release", listOf("reset", "release models"), drained()) // :664-679
        engineState.value = SpeechEngineState.UNAVAILABLE
        advanceTo(5_000)
        assertEquals("nothing reaches the seams any more", emptyList<String>(), drained())
    }
}
