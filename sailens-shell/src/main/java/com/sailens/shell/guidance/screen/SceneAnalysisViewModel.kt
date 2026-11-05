package com.sailens.shell.guidance.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.Bitmap
import android.os.SystemClock
import com.sailens.camera.FrameSource
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.delivery.GuidanceOutputSettings
import com.sailens.guidance.delivery.VisionFaultAlerts
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.processor.decision.SpeechRanks
import com.sailens.guidance.processor.decision.SpeechScale
import com.sailens.guidance.model.scene.SceneResult
import com.sailens.core.log.LogService
import com.sailens.guidance.service.TraceService
import com.sailens.guidance.usecase.decision.AcceptedPromptUseCase
import com.sailens.guidance.usecase.decision.ConfirmDeliveredEventUseCase
import com.sailens.guidance.usecase.decision.ReportCutPromptUseCase
import com.sailens.guidance.usecase.decision.ReportHeardAfterCutUseCase
import com.sailens.guidance.usecase.decision.RevokeUndeliveredEventUseCase
import com.sailens.guidance.usecase.scene.StartSceneAnalysisUseCase
import com.sailens.shell.app.GuidanceStartGate
import com.sailens.guidance.usecase.scene.StopSceneAnalysisUseCase
import com.sailens.shell.diagnostics.GuidanceDiagnosticsStore
import com.sailens.output.AccessibilityStatusProvider
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.shell.device.HapticManager
import com.sailens.shell.device.GuidanceAnnouncements
import com.sailens.shell.device.GuidanceNoticeText
import com.sailens.shell.device.SceneEventText
import com.sailens.shell.device.SceneEventTextResolver
import com.sailens.shell.device.ScreenReaderAnnouncer
import com.sailens.shell.device.SharedEngineOwner
import com.sailens.shell.describe.SceneDescriptionCoordinator
import com.sailens.output.SpeechEngineState
import com.sailens.output.SpeechManager
import com.sailens.shell.device.toSceneEventText
import com.sailens.shell.ext.OverlayBitmapRenderer
import com.sailens.shell.guidance.settings.GuidanceFeedbackSettings
import com.sailens.shell.guidance.overlay.SceneOverlayConfig
import com.sailens.shell.guidance.overlay.SceneOverlayMode
import com.sailens.shell.guidance.safety.GuidanceSystemAlerts
import com.sailens.shell.guidance.safety.OutputFacts
import com.sailens.shell.guidance.settings.GuidanceSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SceneAnalysisViewModel"

/**
 * Drives the live guidance screen only: starting/stopping analysis, speech/haptic feedback, and
 * overlay rendering. Navigation and trace-replay/diagnostics tooling live elsewhere
 * (Nav3 back stack + the debug-only `TraceReplayViewModel`).
 */
class SceneAnalysisViewModel(
    private val frameSource: FrameSource,
    private val startSceneAnalysisUseCase: StartSceneAnalysisUseCase,
    private val stopSceneAnalysisUseCase: StopSceneAnalysisUseCase,
    /**
     * The shell-wide owner of scene description. Guidance preempts through it so the description
     * it cancels is the one actually running, on whichever screen that is.
     */
    private val sceneDescriptionCoordinator: SceneDescriptionCoordinator,
    /** The one route to the screen reader, delivered by the root collector whatever screen is on top. */
    private val screenReaderAnnouncer: ScreenReaderAnnouncer,
    sharedEngineOwner: SharedEngineOwner,
    private val hapticManager: HapticManager,
    private val speechManager: SpeechManager,
    private val logger: LogService,
    private val traceService: TraceService,
    private val sceneOverlayConfig: SceneOverlayConfig,
    private val guidanceSettingsStore: GuidanceSettingsStore,
    private val guidanceDiagnosticsStore: GuidanceDiagnosticsStore,
    private val accessibilityStatusProvider: AccessibilityStatusProvider,
    private val textResolver: SceneEventTextResolver,
    private val announcements: GuidanceAnnouncements,
    /** Tells Guidance a decided event reached the user, so its cooldown record can no longer be taken back. */
    private val confirmDeliveredEvent: ConfirmDeliveredEventUseCase,
    /** Tells Guidance a decided event never reached the user, so its cooldown does not mute it. */
    private val revokeUndeliveredEvent: RevokeUndeliveredEventUseCase,
    /** Tells Guidance a decided event own speech took was cut off. */
    private val reportCutPrompt: ReportCutPromptUseCase,
    private val reportHeardAfterCut: ReportHeardAfterCutUseCase,
    /** Tells Guidance a channel took a decided event. */
    private val acceptedPrompt: AcceptedPromptUseCase,
    private val noticeText: GuidanceNoticeText,
    /** The policy switches, the same ones Guidance decided with. */
    private val detFirst: DetFirstConfig = DetFirstConfig(),
    /** Bound only by debug tooling; null means Guidance may always start. */
    private val startGate: GuidanceStartGate? = null,
    /** The same monotonic clock Guidance stamps events with. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SceneAnalysisUiState(
            isSpeechEnabled = guidanceSettingsStore.settings.value.speechEnabled,
            isHapticsEnabled = guidanceSettingsStore.settings.value.hapticsEnabled,
            isScreenReaderActive = accessibilityStatusProvider.isScreenReaderActive.value,
            showDiagnostics = guidanceDiagnosticsStore.state.value.showDiagnostics,
            enabledOverlayModes = guidanceDiagnosticsStore.state.value.enabledOverlayModes,
            overlayMode = guidanceDiagnosticsStore.state.value.overlayMode,
        )
    )
    val uiState: StateFlow<SceneAnalysisUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<SceneAnalysisUiEffect>()
    val uiEffect: SharedFlow<SceneAnalysisUiEffect> = _uiEffect.asSharedFlow()

    /** The processors are reset only after a stopped run's last frame is done; see [GuidanceRunSequencer]. */
    private val runs = GuidanceRunSequencer(viewModelScope) { stopSceneAnalysisUseCase() }
    private var stallWatchJob: Job? = null

    /**
     * The alerts about Guidance itself, and what this screen does around them. Each event below goes to the entry of
     * the same name, which also feeds the safety monitor: where Guidance stands for the user, observed only.
     */
    private val alerts = GuidanceSystemAlerts(
        cancelStallLoop = ::cancelStallLoop,
        stopGuidanceOutput = { guidanceOutput.stop() },
        clearLatestResult = { latestSceneResult = null },
        preemptDescription = sceneDescriptionCoordinator::preemptForGuidance,
        playAlarm = { haptic -> hapticManager.play(haptic) },
        speakSystemNotice = { text, priority -> speechManager.speakSystemNotice(text, priority = priority) },
        announceToScreenReader = screenReaderAnnouncer::announce,
        noticeText = noticeText::resolve,
        markInterrupted = { _uiState.update { it.copy(wasInterrupted = true) } },
        clearInterrupted = { _uiState.update { it.copy(wasInterrupted = false) } },
        markFailed = { message ->
            _uiState.update {
                it.copy(
                    isRunning = false,
                    isLoading = false,
                    segMask = null,
                    obstacleDetections = emptyList(),
                    activeStatusEvent = null,
                    errorMessage = message,
                )
            }
        },
        isRunning = { _uiState.value.isRunning },
        wasInterrupted = { _uiState.value.wasInterrupted },
        outputSettings = {
            val state = _uiState.value
            GuidanceOutputSettings(
                speechEnabled = state.isSpeechEnabled,
                screenReaderActive = state.isScreenReaderActive,
                hapticsEnabled = state.isHapticsEnabled,
            )
        },
        initialOutput = _uiState.value.outputFacts(),
        recordState = traceService::recordSafetyState,
        recordAlert = traceService::recordGuidanceAlert,
        publishState = { state -> _uiState.update { it.copy(safetyState = state) } },
        timed = { _uiState.value.showDiagnostics },
        logger = logger,
        clock = clock,
        detFirst = detFirst,
    )

    /** Takes each frame's decisions to the user; see [com.sailens.guidance.delivery.GuidanceOutputProtocol]. */
    private val guidanceOutput = GuidanceOutputWiring(
        speakOwn = { announcement, onSpoken, onOutcome, atMs -> speechManager.speak(announcement, onSpoken, onOutcome, atMs) },
        settleFrame = speechManager::settleFrame,
        speechReady = { speechManager.isReady },
        stopSpeech = { speechManager.stop() },
        announcementFor = announcements::announcementFor,
        eventText = { event -> textResolver.resolve(event.toSceneEventText()) },
        noticeText = { messageKey -> textResolver.resolve(SceneEventText(messageKey = messageKey)) },
        // 屏幕阅读器接管时走 shell 唯一的播报出口：描述屏在最上面时，这个屏幕的界面根本没有被
        // 组合，自己收的话这句会被静默丢掉。语速/音量/语言跟随用户在 TalkBack 里的既有设置。
        announceToScreenReader = screenReaderAnnouncer::announce,
        vibrateEvent = hapticManager::trigger,
        vibrateEventAlone = hapticManager::triggerOrQueue,
        playOrReserveVisionFault = { priority, onPlayed ->
            hapticManager.playOrReserve(
                GuidanceHaptic.VISION_UNRELIABLE,
                speechPriority = SpeechScale.of(priority, SpeechRanks.ofVisionFaultRhythm(priority, detFirst)),
                amplitude = maxOf(GuidanceHaptic.amplitudeFor(priority), GuidanceHaptic.amplitudeFor(EventPriority.HIGH)),
                onPlayed = onPlayed,
            )
        },
        cancelVisionFaultReservation = hapticManager::cancelReservation,
        cancelHaptics = hapticManager::cancel,
        // 协调器是整个 shell 共用的那一个，所以打断的就是用户正在听的那一段描述，不管它在哪个屏幕上。
        descriptionHoldsTheFloor = { sceneDescriptionCoordinator.holdsTheFloor },
        preemptDescription = sceneDescriptionCoordinator::preemptForGuidance,
        confirmDelivered = { event -> confirmDeliveredEvent(event) },
        revokeUndelivered = { event, reason -> revokeUndeliveredEvent(event, reason) },
        reportCut = { event -> reportCutPrompt(event) },
        reportHeardAfterCut = { event -> reportHeardAfterCut(event) },
        promptAccepted = { event -> acceptedPrompt(event) },
        outputSettings = {
            val state = _uiState.value
            GuidanceOutputSettings(
                speechEnabled = state.isSpeechEnabled,
                screenReaderActive = state.isScreenReaderActive,
                hapticsEnabled = state.isHapticsEnabled,
            )
        },
        visionFaultSettings = {
            VisionFaultAlerts.Settings(
                speechEnabled = _uiState.value.isSpeechEnabled,
                faultHapticsEnabled = guidanceSettingsStore.settings.value.faultHapticsInEffect,
            )
        },
        logger = logger,
        recordOutcome = traceService::recordPromptOutcome,
        onReachedUser = { event -> _uiState.update { it.copy(lastAnnouncedEvent = event) } },
        detFirst = detFirst,
        recordAlert = { alert -> traceService.recordGuidanceAlert(alert.toTrace(at = System.currentTimeMillis(), elapsedMs = clock())) },
    )
    private var releaseJob: Job? = null
    private var overlayRenderJob: Job? = null
    /**
     * This screen's claim on the shared speech engine. Released by closing it, never by releasing the
     * engine directly: another screen may still be speaking through it.
     */
    private val engineLease = sharedEngineOwner.acquire()
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var frameCount: Long = 0
    private var latestSceneResult: SceneResult? = null
    private var lastOverlayRenderTimeMs: Long = 0L
    private var overlayRenderRequestId: Long = 0L
    private val overlayBitmapRenderer = OverlayBitmapRenderer()

    // State machine to prevent concurrent release/analysis initialization
    @Volatile
    private var isReleasingResources = false

    init {
        speechManager.setSpeechRate(guidanceSettingsStore.settings.value.speechRate)
        if (guidanceSettingsStore.settings.value.speechEnabled) {
            initializeSpeech()
        }
        viewModelScope.launch {
            guidanceSettingsStore.settings.collect { settings ->
                applyFeedbackSettings(settings)
            }
        }
        viewModelScope.launch {
            accessibilityStatusProvider.isScreenReaderActive.collect { active ->
                applyScreenReaderMode(active)
            }
        }
        viewModelScope.launch {
            speechManager.state.collect { engineState ->
                _uiState.update {
                    it.copy(
                        speechEngineState = engineState,
                        isSpeechReady = engineState == SpeechEngineState.READY,
                    )
                }
                checkSpeechUnavailable()
            }
        }
        viewModelScope.launch {
            guidanceDiagnosticsStore.state
                .map { it.overlayMode }
                .distinctUntilChanged()
                .collect { overlayMode ->
                    applyOverlayMode(overlayMode)
                }
        }
    }

    private fun initializeSpeech() {
        // 屏幕阅读器在工作时不初始化自带引擎：播报全部交给它，起一个用不到的 TTS
        // 只会白占资源，还可能在切换时抢音频焦点。
        if (_uiState.value.isScreenReaderActive) {
            logger.info(TAG, "Screen reader active; deferring to it instead of starting own TTS")
            return
        }
        speechManager.initialize {
            logger.debug(TAG, "SpeechManager initialized")
            _uiState.update { it.copy(errorMessage = null) }
        }
    }

    private fun applyScreenReaderMode(active: Boolean) {
        val wasActive = _uiState.value.isScreenReaderActive
        _uiState.update { it.copy(isScreenReaderActive = active) }
        if (active == wasActive) return

        if (active) {
            // 让位给屏幕阅读器，并把自带引擎彻底停掉（含归还音频焦点）。
            speechManager.stop()
        } else if (_uiState.value.isSpeechEnabled) {
            initializeSpeech()
        }
        // Back on own speech: an outage that began under the screen reader is announced now.
        checkSpeechUnavailable()
    }

    /** The output facts as they now stand, to the alerts: once per speech outage they alert; see [GuidanceSystemAlerts.onOutput]. */
    private fun checkSpeechUnavailable() {
        alerts.onOutput(_uiState.value.outputFacts())
    }

    /** The output facts the safety monitor reads, with `isReady` as delivery reads it now. */
    private fun SceneAnalysisUiState.outputFacts() = OutputFacts(
        engineState = speechEngineState,
        speechEnabled = isSpeechEnabled,
        screenReaderActive = isScreenReaderActive,
        hapticsEnabled = isHapticsEnabled,
        speechReady = speechManager.isReady,
    )

    fun toggleAnalysis() {
        when (val action = guidanceToggleAction(_uiState.value.isRunning, startGate)) {
            GuidanceToggleAction.Stop -> stopSceneAnalysis()
            GuidanceToggleAction.Start -> {
                _uiState.startRequested { alerts.onStartRequested() }
                startSceneAnalysis()
            }
            is GuidanceToggleAction.Refuse ->
                viewModelScope.launch { _uiEffect.emit(SceneAnalysisUiEffect.ShowToast(action.reason)) }
        }
    }

    fun setSpeechEnabled(enabled: Boolean) {
        guidanceSettingsStore.setSpeechEnabled(enabled)
    }

    fun setHapticsEnabled(enabled: Boolean) {
        guidanceSettingsStore.setHapticsEnabled(enabled)
    }

    fun setSpeechRate(rate: Float) {
        guidanceSettingsStore.setSpeechRate(rate)
    }

    private fun applyFeedbackSettings(settings: GuidanceFeedbackSettings) {
        val previous = _uiState.value
        _uiState.update {
            it.copy(
                isSpeechEnabled = settings.speechEnabled,
                isHapticsEnabled = settings.hapticsEnabled,
            )
        }

        speechManager.setSpeechRate(settings.speechRate)

        if (!settings.speechEnabled && previous.isSpeechEnabled) {
            speechManager.stop()
        } else if (settings.speechEnabled && !previous.isSpeechEnabled) {
            initializeSpeech()
        }

        if (!settings.hapticsEnabled && previous.isHapticsEnabled) {
            // Navigation rhythms only: an alarm or a vision-fault rhythm already playing is not this switch's.
            hapticManager.cancelNavigation()
        }
        checkSpeechUnavailable()
        // An owed vision-fault rhythm whose switch was just turned off is dropped now, not at the next frame.
        guidanceOutput.protocol.settingsChanged()
    }

    fun setOverlayMode(overlayMode: SceneOverlayMode) {
        guidanceDiagnosticsStore.setOverlayMode(overlayMode)
    }

    private fun applyOverlayMode(overlayMode: SceneOverlayMode) {
        lastOverlayRenderTimeMs = 0L
        val effectiveOverlayMode = sceneOverlayConfig.coerceEnabledMode(overlayMode)
        if (effectiveOverlayMode == SceneOverlayMode.OFF) {
            overlayRenderRequestId++
            overlayRenderJob?.cancel()
            overlayRenderJob = null
            _uiState.update {
                it.copy(
                    overlayMode = SceneOverlayMode.OFF,
                    segMask = null,
                    maskSourceAgeMs = 0L,
                    obstacleDetections = emptyList(),
                )
            }
            return
        }

        val result = latestSceneResult
        _uiState.update {
            it.copy(
                overlayMode = effectiveOverlayMode,
                segMask = null,
                maskSourceAgeMs = 0L,
                obstacleDetections = result?.obstacleDetectionsForOverlay(effectiveOverlayMode).orEmpty(),
            )
        }
        scheduleOverlayRender(result, effectiveOverlayMode, force = true)
    }

    private fun startSceneAnalysis() {
        frameCount = 0
        runs.start {
            // Wait for ongoing resource release to complete (with timeout protection)
            var waitTime = 0L
            val maxWaitMs = 2000L
            while (isReleasingResources && waitTime < maxWaitMs) {
                delay(50)
                waitTime += 50
            }
            if (isReleasingResources) {
                logger.warning(TAG, "Resource release timeout; proceeding with analysis anyway")
            }

            startStallWatch()

            // collectLatest is often used for high-frequency data, discarding previous incomplete processing
            startSceneAnalysisUseCase(
                frameSource.frames,
                releaseFrame = frameSource::releaseFrame,
                // The class-map overlay is the only reader of the mask outside the pipeline; the
                // copy it needs is made only while it is showing.
                semanticMaskSnapshot = { rendersSemanticClassMask() },
            ).onStart {
                _uiState.flowStarted { alerts.onFlowStarted() }
                logger.info(TAG, "Scene analysis started")
            }.catch { e ->
                alerts.onFlowFailed(e)
            }.collectLatest { result ->
                frameCount++
                // Before the frame's prompts are delivered.
                alerts.onResult(clock(), result.qualification.baseline, speechReady = speechManager.isReady)
                latestSceneResult = result
                val overlayMode = _uiState.value.overlayMode
                val events = result.events
                val latestSceneDebugInfo = result.debugInfo.takeIf {
                    sceneOverlayConfig.enableDebugPanel && guidanceDiagnosticsStore.state.value.showDiagnostics
                }
                _uiState.update {
                    it.withFrameEvents(events, clock()).copy(
                        frameDisplayWidth = result.frameDisplayWidth,
                        frameDisplayHeight = result.frameDisplayHeight,
                        obstacleDetections = result.obstacleDetectionsForOverlay(overlayMode),
                        latestSceneDebugInfo = latestSceneDebugInfo,
                    )
                }
                scheduleOverlayRender(result, overlayMode)
                if (frameCount == 1L) {
                    logger.info(
                        TAG,
                        "First frame diagnostics",
                        mapOf(
                            "semanticProvider" to (result.debugInfo?.semanticProvider ?: "unknown"),
                            "obstacleProvider" to (result.debugInfo?.obstacleProvider ?: "unknown"),
                            "perceptionProfile" to (result.debugInfo?.perceptionProfile ?: "unknown"),
                            "trackedObstacles" to result.obstacles.size,
                            "rawDetections" to result.obstacleDetections.size,
                        )
                    )
                }
                guidanceOutput.protocol.onFrame(events, result.sequenceNumber, result.visionFaults)
            }
        }
    }

    /** The stall loop: the watch is the alerts', this view model only keeps time for it. */
    private fun startStallWatch() {
        stallWatchJob?.cancel()
        alerts.onStallWatchStarted(clock())
        stallWatchJob = viewModelScope.launch {
            while (true) {
                delay(GuidanceStallDetector.POLL_INTERVAL_MS)
                alerts.poll(clock())
            }
        }
    }

    private fun stopStallWatch() {
        cancelStallLoop()
        alerts.onStallWatchStopped()
    }

    private fun cancelStallLoop() {
        stallWatchJob?.cancel()
        stallWatchJob = null
    }

    /**
     * The app went to the background while this screen was up; the alert, if Guidance runs, is
     * [GuidanceSystemAlerts.onLifecycleStop]'s.
     */
    fun onGuidanceInterrupted(noticeText: String) {
        alerts.onLifecycleStop(noticeText)
    }

    /** Back in the foreground; see [GuidanceSystemAlerts.onLifecycleStart]. */
    fun acknowledgeInterruption() {
        alerts.onLifecycleStart(clock())
    }

    /**
     * 重播最近一条提示。
     *
     * 走路时漏听是常态——风声、车声、正好在过马路。没有重播就只能等下一次冷却结束，
     * 而那可能是好几秒之后，或者障碍已经过去、再也不播了。
     *
     * 这里刻意**不检查 expiresAt**：过期检查是为了防止自动播报滞后于场景，而重播是用户
     * 主动要求的，他要的就是"刚才那句到底说了什么"。
     */
    fun replayLastGuidance() {
        val state = _uiState.value
        val event = state.lastAnnouncedEvent ?: return
        if (!state.isSpeechEnabled) {
            // 语音关着时用震动重放同一个符号，纯震动模式下重播同样可用。
            if (state.isHapticsEnabled) hapticManager.trigger(event)
            return
        }

        val text = textResolver.resolve(event.toSceneEventText())
        if (state.isScreenReaderActive) {
            screenReaderAnnouncer.announce(text)
        } else {
            speechManager.speakSystemNotice(text)
        }
    }

    private fun stopSceneAnalysis() {
        stopStallWatch()
        // Stopped on purpose: no recovery is said for what was still faulty.
        guidanceOutput.stop {
            runs.stop()
            overlayRenderRequestId++
            overlayRenderJob?.cancel()
            overlayRenderJob = null
            latestSceneResult = null
        }
        _uiState.stopped { alerts.onStopped() }
    }

    override fun onCleared() {
        alerts.onStopped()
        stopStallWatch()
        guidanceOutput.protocol.reset()
        overlayRenderJob?.cancel()
        overlayRenderJob = null
        hapticManager.cancel()
        releaseSceneAnalysisResources()
        // The engine is shared with the Describe screen. Give back this screen's claim; the owner
        // stops and releases it once nobody holds one, and not while another screen still speaks.
        engineLease.close()
        super.onCleared()
    }

    private fun scheduleOverlayRender(
        result: SceneResult?,
        overlayMode: SceneOverlayMode,
        force: Boolean = false,
    ) {
        if (result == null || !sceneOverlayConfig.isModeEnabled(overlayMode) || !overlayMode.rendersBitmap()) {
            overlayRenderRequestId++
            overlayRenderJob?.cancel()
            overlayRenderJob = null
            if (_uiState.value.segMask != null) {
                _uiState.update {
                    it.copy(
                        segMask = null,
                        maskSourceAgeMs = 0L,
                    )
                }
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastOverlayRenderTimeMs < sceneOverlayConfig.bitmapRenderIntervalMs) {
            return
        }
        lastOverlayRenderTimeMs = now

        overlayRenderJob?.cancel()
        val requestId = ++overlayRenderRequestId
        overlayRenderJob = viewModelScope.launch {
            val renderStartAt = SystemClock.elapsedRealtime()
            val bitmap = withContext(Dispatchers.Default) {
                result.toOverlayBitmap(overlayMode)
            }
            val renderCompletedAt = SystemClock.elapsedRealtime()
            val renderCompletedWallClockAt = System.currentTimeMillis()
            val sourceAgeMs = overlaySourceAgeMs(
                sourcePipelineCompletedAt = result.pipelineCompletedAt,
                renderedAt = renderCompletedWallClockAt,
            )
            traceService.recordOverlayRender(
                renderedAt = renderCompletedWallClockAt,
                renderMs = renderCompletedAt - renderStartAt,
                overlayMode = overlayMode.name,
                bitmapRendered = bitmap != null,
                sourceSequenceNumber = result.sequenceNumber,
                sourcePipelineCompletedAt = result.pipelineCompletedAt,
                sourceAgeMs = sourceAgeMs,
            )
            if (_uiState.value.overlayMode == overlayMode && requestId == overlayRenderRequestId) {
                _uiState.update {
                    it.copy(
                        segMask = bitmap,
                        maskSourceAgeMs = sourceAgeMs,
                    )
                }
            }
        }
    }

    private fun releaseSceneAnalysisResources() {
        releaseJob?.cancel()
        isReleasingResources = true
        // viewModelScope is already cancelled here, so the run is stopped in releaseScope: the reset
        // waits for its last frame, and the models are released only after that frame is done.
        releaseJob = runs.stop(on = releaseScope) {
            try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        stopSceneAnalysisUseCase.release()
                    }.onFailure { error ->
                        logger.error(TAG, "Error releasing scene analysis resources", error)
                    }
                    // The scene-description model is not released here any more: it is shared with
                    // the Describe screen, and SharedEngineOwner releases it when the last screen
                    // that could use it is gone.
                }
            } finally {
                isReleasingResources = false
                logger.info(TAG, "Scene analysis resources released")
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause != null) {
                    logger.error(TAG, "Release job failed", cause)
                    isReleasingResources = false
                }
                releaseScope.cancel()
            }
        }
    }

    private fun SceneResult.obstacleDetectionsForOverlay(overlayMode: SceneOverlayMode) =
        if (!sceneOverlayConfig.isModeEnabled(overlayMode)) {
            emptyList()
        } else {
            when (overlayMode) {
                SceneOverlayMode.DETECTION_BOXES -> obstacleDetections
                SceneOverlayMode.OFF,
                SceneOverlayMode.PASSABLE_AREA_MASK,
                SceneOverlayMode.SEMANTIC_CLASS_MASK -> emptyList()
            }
        }

    private fun SceneResult.toOverlayBitmap(overlayMode: SceneOverlayMode): Bitmap? {
        val sourceAspectRatio = frameDisplayWidth?.let { width ->
            frameDisplayHeight?.takeIf { it > 0 }?.let { height ->
                width.toFloat() / height
            }
        }
        return when (overlayMode) {
            SceneOverlayMode.PASSABLE_AREA_MASK ->
                passableMask?.let { overlayBitmapRenderer.renderPassableMask(it, sourceAspectRatio) }
            SceneOverlayMode.SEMANTIC_CLASS_MASK ->
                segmentationMask?.let { overlayBitmapRenderer.renderSemanticClasses(it, sourceAspectRatio) }
            SceneOverlayMode.OFF,
            SceneOverlayMode.DETECTION_BOXES -> null
        }
    }

    /** Read from the pipeline thread, once per frame. */
    private fun rendersSemanticClassMask(): Boolean =
        _uiState.value.overlayMode == SceneOverlayMode.SEMANTIC_CLASS_MASK &&
            sceneOverlayConfig.isModeEnabled(SceneOverlayMode.SEMANTIC_CLASS_MASK)

    private fun SceneOverlayMode.rendersBitmap(): Boolean {
        return when (this) {
            SceneOverlayMode.PASSABLE_AREA_MASK,
            SceneOverlayMode.SEMANTIC_CLASS_MASK -> true
            SceneOverlayMode.OFF,
            SceneOverlayMode.DETECTION_BOXES -> false
        }
    }

    private fun overlaySourceAgeMs(sourcePipelineCompletedAt: Long, renderedAt: Long): Long {
        if (sourcePipelineCompletedAt <= 0L) return 0L
        return (renderedAt - sourcePipelineCompletedAt).coerceAtLeast(0L)
    }

}
