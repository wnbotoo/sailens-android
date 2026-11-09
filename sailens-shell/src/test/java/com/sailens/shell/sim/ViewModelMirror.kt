package com.sailens.shell.sim

import com.sailens.core.frame.ImageFrame
import com.sailens.core.log.LogService
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.delivery.GuidanceOutputSettings
import com.sailens.guidance.delivery.VisionFaultAlerts
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneResult
import com.sailens.guidance.processor.decision.SpeechRanks
import com.sailens.guidance.processor.decision.SpeechScale
import com.sailens.output.Announcement
import com.sailens.output.SpeechEngineState
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.shell.device.GuidanceNotice
import com.sailens.shell.device.SceneEventText
import com.sailens.shell.device.SharedEngineOwner
import com.sailens.shell.device.toSceneEventText
import com.sailens.shell.guidance.safety.GuidanceSystemAlerts
import com.sailens.shell.guidance.safety.OutputFacts
import com.sailens.shell.guidance.screen.GuidanceOutputWiring
import com.sailens.shell.guidance.screen.GuidanceRunSequencer
import com.sailens.shell.guidance.screen.GuidanceStallDetector
import com.sailens.shell.guidance.screen.GuidanceToggleAction
import com.sailens.shell.guidance.screen.SceneAnalysisUiState
import com.sailens.shell.guidance.screen.flowStarted
import com.sailens.shell.guidance.screen.guidanceToggleAction
import com.sailens.shell.guidance.screen.startRequested
import com.sailens.shell.guidance.screen.stopped
import com.sailens.shell.guidance.screen.toTrace
import com.sailens.shell.guidance.screen.withFrameEvents
import com.sailens.shell.guidance.settings.GuidanceFeedbackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

private const val TAG = "SceneAnalysisViewModel"

/** `StartSceneAnalysisUseCase.invoke`, as the view model calls it. */
internal typealias AnalysisPipeline =
    suspend (frames: Flow<ImageFrame>, releaseFrame: (ImageFrame) -> Unit, semanticMaskSnapshot: () -> Boolean) -> Flow<SceneResult>

/**
 * `SceneAnalysisViewModel`, mirrored: its forwarding, entry by entry, onto the real system alerts, output wiring,
 * run sequencer, UI state and its three forwarding helpers, which the view model cannot be built without an Android
 * `Context` to reach. Every step names the line of `SceneAnalysisViewModel.kt` it mirrors. Kept in step with the
 * file by `SimulatorMirrorPinTest`; that the steps are the view model's, in its order, is `MirrorOrderTest`'s.
 *
 * Left out, because they only draw: the overlay (its collector, renders and the semantic-mask copy, which is never
 * asked for here) and the debug panel's info. The first-frame diagnostics log is left out too.
 *
 * What the mirror adds is observation only, never a call the view model makes: [observer] hears a run asked for and
 * its block beginning, each result arriving and handled, and each reset a stop asks for; the alerts' four ports onto
 * the UI state are recorded as they are written.
 *
 * The view model's own scope runs in [context], the session's one thread, as `viewModelScope` runs on the main
 * thread, and so does its release scope (`Dispatchers.IO` on a phone), so that a session is one ordered thread; the
 * models' release runs there too instead of in `withContext(Dispatchers.IO)`. A failure no coroutine catches goes to
 * [context]'s handler, as it would crash the app.
 *
 * @param seams how the real alerts and wiring built below are reached; a recorder in the mirror's own test.
 */
internal class ViewModelMirror(
    private val frames: (run: Int) -> Flow<ImageFrame>,
    private val analysis: AnalysisPipeline,
    private val resetProcessors: () -> Unit,
    private val releaseModels: suspend () -> Unit,
    private val description: DescriptionDevice,
    private val announceToScreenReader: (String) -> Unit,
    sharedEngineOwner: SharedEngineOwner,
    private val haptics: HapticDevice,
    private val speech: SpeechDevice,
    private val logger: LogService,
    private val record: SessionRecord,
    private val settings: StateFlow<GuidanceFeedbackSettings>,
    private val screenReaderActive: StateFlow<Boolean>,
    private val textResolver: (SceneEventText) -> String,
    private val announcements: (SceneEvent) -> Announcement,
    private val cooldown: CooldownReceipts,
    private val noticeText: (GuidanceNotice) -> String,
    private val detFirst: DetFirstConfig,
    private val clock: () -> Long,
    context: CoroutineContext,
    private val observer: MirrorObserver,
    seams: MirrorSeams,
) {
    // SceneAnalysisViewModel.kt:115-124 (the diagnostics store's fields keep their defaults: diagnostics off)
    val uiState = MutableStateFlow(
        SceneAnalysisUiState(
            isSpeechEnabled = settings.value.speechEnabled,
            isHapticsEnabled = settings.value.hapticsEnabled,
            isScreenReaderActive = screenReaderActive.value,
        )
    )

    /** `viewModelScope`: cancelled when the screen goes, before [onCleared]. */
    val viewModelScope = CoroutineScope(SupervisorJob() + context)

    // SceneAnalysisViewModel.kt:131
    private val runs = GuidanceRunSequencer(viewModelScope) { resetProcessors() }
    private var stallWatchJob: Job? = null

    // SceneAnalysisViewModel.kt:139-181
    private val alerts: MirrorAlerts = seams.alerts {
        GuidanceSystemAlerts(
            cancelStallLoop = ::cancelStallLoop,
            stopGuidanceOutput = { guidanceOutput.stop {} },
            clearLatestResult = {
                latestSceneResult = null
                record.ui("clear_latest_result")
            },
            preemptDescription = description::preemptForGuidance,
            playAlarm = { haptic -> haptics.play(haptic) },
            speakSystemNotice = { text, priority -> speech.speakSystemNotice(text, priority = priority) },
            announceToScreenReader = announceToScreenReader,
            noticeText = noticeText,
            markInterrupted = {
                uiState.update { it.copy(wasInterrupted = true) }
                record.ui("mark_interrupted")
            },
            clearInterrupted = {
                uiState.update { it.copy(wasInterrupted = false) }
                record.ui("clear_interrupted")
            },
            markFailed = { message ->
                uiState.update {
                    it.copy(
                        isRunning = false,
                        isLoading = false,
                        segMask = null,
                        obstacleDetections = emptyList(),
                        activeStatusEvent = null,
                        errorMessage = message,
                    )
                }
                record.ui("mark_failed", message)
            },
            isRunning = { uiState.value.isRunning },
            wasInterrupted = { uiState.value.wasInterrupted },
            outputSettings = {
                val state = uiState.value
                GuidanceOutputSettings(
                    speechEnabled = state.isSpeechEnabled,
                    screenReaderActive = state.isScreenReaderActive,
                    hapticsEnabled = state.isHapticsEnabled,
                )
            },
            speechReady = { speech.isReady },
            initialOutput = uiState.value.outputFacts(),
            recordState = record::recordSafetyState,
            recordAlert = record::recordGuidanceAlert,
            publishState = { state -> uiState.update { it.copy(safetyState = state) } },
            timed = { uiState.value.showDiagnostics },
            logger = logger,
            clock = clock,
            detFirst = detFirst,
            // The trace's wall clock and the main-thread timing reach no line.
            wallClock = { 0L },
            nanoClock = { 0L },
        )
    }

    // SceneAnalysisViewModel.kt:184-234
    private val guidanceOutput: MirrorOutput = seams.output {
        GuidanceOutputWiring(
            speakOwn = { announcement, onSpoken, onOutcome, atMs -> speech.speak(announcement, onSpoken, onOutcome, atMs) },
            settleFrame = speech::settleFrame,
            speechReady = { speech.isReady },
            stopSpeech = { speech.stop() },
            announcementFor = announcements,
            eventText = { event -> textResolver(event.toSceneEventText()) },
            noticeText = { messageKey -> textResolver(SceneEventText(messageKey = messageKey)) },
            announceToScreenReader = announceToScreenReader,
            vibrateEvent = haptics::trigger,
            vibrateEventAlone = haptics::triggerOrQueue,
            playOrReserveVisionFault = { priority, onPlayed ->
                haptics.playOrReserve(
                    GuidanceHaptic.VISION_UNRELIABLE,
                    speechPriority = SpeechScale.of(priority, SpeechRanks.ofVisionFaultRhythm(priority, detFirst)),
                    amplitude = maxOf(GuidanceHaptic.amplitudeFor(priority), GuidanceHaptic.amplitudeFor(EventPriority.HIGH)),
                    onPlayed = onPlayed,
                )
            },
            cancelVisionFaultReservation = haptics::cancelReservation,
            cancelHaptics = haptics::cancel,
            descriptionHoldsTheFloor = { description.holdsTheFloor },
            preemptDescription = description::preemptForGuidance,
            confirmDelivered = { event -> cooldown.confirm(event) },
            revokeUndelivered = { event, reason -> cooldown.revoke(event, reason) },
            reportCut = { event -> cooldown.reportCut(event) },
            reportHeardAfterCut = { event -> cooldown.reportHeardAfterCut(event) },
            promptAccepted = { event -> cooldown.accepted(event) },
            outputSettings = {
                val state = uiState.value
                GuidanceOutputSettings(
                    speechEnabled = state.isSpeechEnabled,
                    screenReaderActive = state.isScreenReaderActive,
                    hapticsEnabled = state.isHapticsEnabled,
                )
            },
            visionFaultSettings = {
                VisionFaultAlerts.Settings(
                    speechEnabled = uiState.value.isSpeechEnabled,
                    faultHapticsEnabled = settings.value.faultHapticsInEffect,
                )
            },
            logger = logger,
            recordOutcome = record::recordPromptOutcome,
            onReachedUser = { event -> uiState.update { it.copy(lastAnnouncedEvent = event) } },
            detFirst = detFirst,
            // The wall clock (`System.currentTimeMillis()` there) reaches no line.
            recordAlert = { alert -> record.recordGuidanceAlert(alert.toTrace(at = 0L, elapsedMs = clock())) },
        )
    }

    private var releaseJob: Job? = null

    // SceneAnalysisViewModel.kt:241-242
    private val engineLease = sharedEngineOwner.acquire()
    val releaseScope = CoroutineScope(SupervisorJob() + context)
    private var frameCount: Long = 0
    private var latestSceneResult: SceneResult? = null

    // SceneAnalysisViewModel.kt:250-251
    @Volatile
    private var isReleasingResources = false

    // SceneAnalysisViewModel.kt:253-287 (the overlay's collector left out)
    init {
        speech.setSpeechRate(settings.value.speechRate)
        if (settings.value.speechEnabled) {
            initializeSpeech()
        }
        viewModelScope.launch {
            settings.collect { settings ->
                applyFeedbackSettings(settings)
            }
        }
        viewModelScope.launch {
            screenReaderActive.collect { active ->
                applyScreenReaderMode(active)
            }
        }
        viewModelScope.launch {
            speech.state.collect { engineState ->
                uiState.update {
                    it.copy(
                        speechEngineState = engineState,
                        isSpeechReady = engineState == SpeechEngineState.READY,
                    )
                }
                checkSpeechUnavailable()
            }
        }
    }

    // SceneAnalysisViewModel.kt:289-300
    private fun initializeSpeech() {
        if (uiState.value.isScreenReaderActive) {
            logger.info(TAG, "Screen reader active; deferring to it instead of starting own TTS")
            return
        }
        speech.initialize()
    }

    // SceneAnalysisViewModel.kt:302-315
    private fun applyScreenReaderMode(active: Boolean) {
        val wasActive = uiState.value.isScreenReaderActive
        uiState.update { it.copy(isScreenReaderActive = active) }
        if (active == wasActive) return

        if (active) {
            speech.stop()
        } else if (uiState.value.isSpeechEnabled) {
            initializeSpeech()
        }
        checkSpeechUnavailable()
    }

    // SceneAnalysisViewModel.kt:318-321
    private fun checkSpeechUnavailable() {
        val state = uiState.value
        alerts.onOutput(state.speechEngineState, state.isSpeechEnabled, state.isScreenReaderActive, state.isHapticsEnabled)
    }

    // SceneAnalysisViewModel.kt:324-330
    private fun SceneAnalysisUiState.outputFacts() = OutputFacts(
        engineState = speechEngineState,
        speechEnabled = isSpeechEnabled,
        screenReaderActive = isScreenReaderActive,
        hapticsEnabled = isHapticsEnabled,
        speechReady = speech.isReady,
    )

    // SceneAnalysisViewModel.kt:332-342 (no start gate is bound here)
    fun toggleAnalysis() {
        when (val action = guidanceToggleAction(uiState.value.isRunning, startGate = null)) {
            GuidanceToggleAction.Stop -> stopSceneAnalysis()
            GuidanceToggleAction.Start -> {
                uiState.startRequested { alerts.onStartRequested() }
                startSceneAnalysis()
            }
            is GuidanceToggleAction.Refuse -> error("no start gate is bound, so nothing refuses: ${action.reason}")
        }
    }

    // SceneAnalysisViewModel.kt:356-380
    private fun applyFeedbackSettings(settings: GuidanceFeedbackSettings) {
        val previous = uiState.value
        uiState.update {
            it.copy(
                isSpeechEnabled = settings.speechEnabled,
                isHapticsEnabled = settings.hapticsEnabled,
            )
        }

        speech.setSpeechRate(settings.speechRate)

        if (!settings.speechEnabled && previous.isSpeechEnabled) {
            speech.stop()
        } else if (settings.speechEnabled && !previous.isSpeechEnabled) {
            initializeSpeech()
        }

        if (!settings.hapticsEnabled && previous.isHapticsEnabled) {
            haptics.cancelNavigation()
        }
        checkSpeechUnavailable()
        guidanceOutput.settingsChanged()
    }

    // SceneAnalysisViewModel.kt:416-479
    private fun startSceneAnalysis() {
        frameCount = 0
        val run = observer.runRequested()
        runs.start {
            observer.blockStarted(run, coroutineContext.job)
            // :419-428
            var waitTime = 0L
            val maxWaitMs = 2000L
            while (isReleasingResources && waitTime < maxWaitMs) {
                delay(50)
                waitTime += 50
            }
            if (isReleasingResources) {
                logger.warning(TAG, "Resource release timeout; proceeding with analysis anyway")
            }

            // :430
            startStallWatch()

            // :433-444 (frames are not lent here, so there is nothing to give back)
            analysis(
                frames(run),
                {},
                { false },
            ).onStart {
                uiState.flowStarted { alerts.onFlowStarted() }
                logger.info(TAG, "Scene analysis started")
            }.catch { e ->
                alerts.onFlowFailed(e)
            }.collectLatest { result ->
                observer.resultArrived(run, result)
                // :445-461
                frameCount++
                alerts.onResult(result.qualification.baseline, result.sequenceNumber, result.frameReceivedAtMs)
                latestSceneResult = result
                val events = result.events
                uiState.update {
                    it.withFrameEvents(events, clock()).copy(
                        frameDisplayWidth = result.frameDisplayWidth,
                        frameDisplayHeight = result.frameDisplayHeight,
                    )
                }
                // :476
                guidanceOutput.onFrame(events, result.sequenceNumber, result.visionFaults)
                observer.resultHandled(run, result.sequenceNumber)
            }
        }
    }

    // SceneAnalysisViewModel.kt:482-491
    private fun startStallWatch() {
        stallWatchJob?.cancel()
        alerts.onStallWatchStarted()
        stallWatchJob = viewModelScope.launch {
            while (true) {
                delay(GuidanceStallDetector.POLL_INTERVAL_MS)
                alerts.poll()
            }
        }
    }

    // SceneAnalysisViewModel.kt:493-496
    private fun stopStallWatch() {
        cancelStallLoop()
        alerts.onStallWatchStopped()
    }

    // SceneAnalysisViewModel.kt:498-501
    fun cancelStallLoop() {
        stallWatchJob?.cancel()
        stallWatchJob = null
    }

    // SceneAnalysisViewModel.kt:507-509
    fun onGuidanceInterrupted(noticeText: String) {
        alerts.onLifecycleStop(noticeText)
    }

    // SceneAnalysisViewModel.kt:512-514
    fun acknowledgeInterruption() {
        alerts.onLifecycleStart()
    }

    // SceneAnalysisViewModel.kt:542-555 (the overlay's render is not mirrored)
    private fun stopSceneAnalysis() {
        stopStallWatch()
        guidanceOutput.stop {
            observer.stopIssued(runs.stop())
            latestSceneResult = null
        }
        uiState.stopped { alerts.onStopped() }
        alerts.endOfSession()
    }

    /**
     * SceneAnalysisViewModel.kt:557-570. The real `viewModelScope` is cancelled before `onCleared` runs (Android's
     * `ViewModel` contract; the view model relies on it at :633), so this cancels it first: the state-flow
     * collectors, the stall loop and the analysis collector run no more after it.
     */
    fun onCleared() {
        viewModelScope.cancel()
        alerts.onStopped()
        alerts.endOfSession()
        stopStallWatch()
        guidanceOutput.reset()
        haptics.cancel()
        releaseSceneAnalysisResources()
        engineLease.close()
    }

    // SceneAnalysisViewModel.kt:630-660 (the release runs in the release scope itself, not in Dispatchers.IO)
    private fun releaseSceneAnalysisResources() {
        releaseJob?.cancel()
        isReleasingResources = true
        releaseJob = runs.stop(on = releaseScope) {
            try {
                runCatching {
                    releaseModels()
                }.onFailure { error ->
                    logger.error(TAG, "Error releasing scene analysis resources", error)
                }
            } finally {
                isReleasingResources = false
                logger.info(TAG, "Scene analysis resources released")
            }
        }.also { job ->
            observer.stopIssued(job)
            job.invokeOnCompletion { cause ->
                if (cause != null) {
                    logger.error(TAG, "Release job failed", cause)
                    isReleasingResources = false
                }
                releaseScope.cancel()
            }
        }
    }
}
