package com.sailens.shell.guidance.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.Bitmap
import android.os.SystemClock
import com.sailens.camera.FrameSource
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneResult
import com.sailens.core.log.LogService
import com.sailens.guidance.service.TraceService
import com.sailens.guidance.usecase.decision.RevokeUndeliveredEventUseCase
import com.sailens.guidance.usecase.scene.StartSceneAnalysisUseCase
import com.sailens.guidance.usecase.scene.StopSceneAnalysisUseCase
import com.sailens.guidance.util.Timestamp
import com.sailens.shell.diagnostics.GuidanceDiagnosticsStore
import com.sailens.output.AccessibilityStatusProvider
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.shell.device.HapticManager
import com.sailens.shell.device.GuidanceAnnouncements
import com.sailens.shell.device.GuidanceNotice
import com.sailens.shell.device.GuidanceNoticeText
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
    /** Tells Guidance a decided event never reached the user, so its cooldown does not mute it. */
    private val revokeUndeliveredEvent: RevokeUndeliveredEventUseCase,
    private val noticeText: GuidanceNoticeText,
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

    private var analysisJob: Job? = null
    private var stallWatchJob: Job? = null
    private val stallDetector = GuidanceStallDetector()
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
                val previousState = _uiState.value
                val shouldAlert = shouldAlertSpeechUnavailable(
                    previousEngineState = previousState.speechEngineState,
                    newEngineState = engineState,
                    speechEnabled = previousState.isSpeechEnabled,
                    screenReaderActive = previousState.isScreenReaderActive,
                )
                _uiState.update {
                    it.copy(
                        speechEngineState = engineState,
                        isSpeechReady = engineState == SpeechEngineState.READY,
                    )
                }
                if (shouldAlert) {
                    onSpeechEngineUnavailable()
                }
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
    }

    fun toggleAnalysis() {
        if (_uiState.value.isRunning) {
            stopSceneAnalysis()
        } else {
            _uiState.update { it.copy(isLoading = true) }
            startSceneAnalysis()
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
            hapticManager.cancel()
        }
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
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
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
                _uiState.update {
                    it.copy(isInitializing = false, isRunning = true, isLoading = false)
                }
                logger.info(TAG, "Scene analysis started")
            }.catch { e ->
                logger.error(TAG, "Error in scene analysis", e)
                stopStallWatch()
                speechManager.stop()
                hapticManager.cancel()
                latestSceneResult = null
                // 卡片对盲人不存在：这条失效必须出声/出震，而不只是把状态改成错误。
                alertGuidanceUnavailable(GuidanceNotice.FAILED)
                // Keep the raw cause in state + logs (logger.error above), but surface only a
                // friendly, localized message to the user via the status card
                // (error_analysis_start_failed). No raw-exception toast: it leaks internals and is
                // not useful to a screen-reader user; the persistent error card already communicates it.
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        isLoading = false,
                        segMask = null,
                        obstacleDetections = emptyList(),
                        activeStatusEvent = null,
                        errorMessage = e.message ?: "Unknown error"
                    )
                }
            }.collectLatest { result ->
                frameCount++
                stallDetector.onResult(clock())
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
                onSceneEvents(events, result.sequenceNumber)
            }
        }
    }

    private fun onSceneEvents(events: List<SceneEvent>, sequenceNumber: Long) {
        if (events.isEmpty()) return
        // Guidance 为这一条（且只为这一条）记了冷却，见 DecideEventsUseCase。
        val primaryEvent = events.first()

        val state = _uiState.value

        logger.debug(TAG, "Scene events generated, ${primaryEvent.messageKey}", mapOf("count" to events.size))

        // 等描述 / 打断描述 / 送达 / 没送达就撤销冷却，并为这条提示写一条 prompt_outcome（实际送达的
        // 通道或撤回原因）——整条规则在 offerAndTraceGuidancePrompt 里，有单测。协调器是整个 shell
        // 共用的那一个，所以打断的就是用户正在听的那一段描述，不管它在哪个屏幕上。
        val delivered = offerAndTraceGuidancePrompt(
            event = primaryEvent,
            sourceSequenceNumber = sequenceNumber,
            outputs = GuidanceOutputSettings(
                speechEnabled = state.isSpeechEnabled,
                screenReaderActive = state.isScreenReaderActive,
                hapticsEnabled = state.isHapticsEnabled,
            ),
            descriptionHoldsTheFloor = sceneDescriptionCoordinator.holdsTheFloor,
            now = Timestamp::now,
            preemptDescription = { sceneDescriptionCoordinator.preemptForGuidance(primaryEvent.messageKey) },
            deliver = { deliver(primaryEvent, state) },
            revoke = { revokeUndeliveredEvent(primaryEvent) },
            record = traceService::recordPromptOutcome,
        )
        if (!delivered) return

        if (state.hasGuidanceOutputChannel()) {
            _uiState.update { it.copy(lastAnnouncedEvent = primaryEvent) }
        }
    }

    /**
     * 把 [event] 送到用户可感知的通道，见 [deliverGuidanceEvent]。
     *
     * @return 真正接收了它的通道；为空表示没送达。
     */
    private fun deliver(event: SceneEvent, state: SceneAnalysisUiState): GuidanceDeliveryResult = deliverGuidanceEvent(
        speechEnabled = state.isSpeechEnabled,
        screenReaderActive = state.isScreenReaderActive,
        speechEngineReady = speechManager.isReady,
        hapticsEnabled = state.isHapticsEnabled,
        // 屏幕阅读器接管时走 shell 唯一的播报出口：描述屏在最上面时，这个屏幕的界面根本没有被
        // 组合，自己收的话这句会被静默丢掉。语速/音量/语言跟随用户在 TalkBack 里的既有设置。
        announceToScreenReader = {
            screenReaderAnnouncer.announce(textResolver.resolve(event.toSceneEventText()))
        },
        speak = { speechManager.speak(announcements.announcementFor(event)) },
        vibrate = { hapticManager.trigger(event) },
    )

    /**
     * Guidance 在运行中失效：分析出错而停止，或长时间没有产出结果。
     *
     * 与 [onGuidanceInterrupted] 同一个道理：卡片对盲人不存在，"收不到提示"与"前方安全"是同一种
     * 体验。震动不看 hapticsEnabled 开关，理由同 [onSpeechEngineUnavailable]——这是"应用无法工作"
     * 的告知，不是导航提示。
     */
    private fun alertGuidanceUnavailable(notice: GuidanceNotice) {
        logger.warning(TAG, "Guidance unavailable while running", mapOf("notice" to notice.name))
        sceneDescriptionCoordinator.preemptForGuidance("guidance ${notice.name.lowercase()}")
        hapticManager.play(GuidanceHaptic.INTERRUPTED)

        val state = _uiState.value
        if (!state.isSpeechEnabled) return
        val text = noticeText.resolve(notice)
        if (state.isScreenReaderActive) {
            screenReaderAnnouncer.announce(text)
        } else {
            speechManager.speakSystemNotice(text, priority = notice.speechPriority)
        }
    }

    private fun startStallWatch() {
        stallWatchJob?.cancel()
        stallDetector.start(clock())
        stallWatchJob = viewModelScope.launch {
            while (true) {
                delay(GuidanceStallDetector.POLL_INTERVAL_MS)
                // 进后台时 onGuidanceInterrupted 已经告知过；那时相机停帧是必然的，不重复报。
                if (_uiState.value.wasInterrupted) continue
                if (stallDetector.alarmDue(clock())) {
                    alertGuidanceUnavailable(GuidanceNotice.STALLED)
                }
            }
        }
    }

    private fun stopStallWatch() {
        stallWatchJob?.cancel()
        stallWatchJob = null
        stallDetector.stop()
    }

    /**
     * 分析在用户没有主动停止的情况下断了（息屏、来电、被系统回收）。
     *
     * 这是整条链路最危险的失效：用户以为辅助还在跑，实际上已经什么都不检测了，而"没有提示"
     * 恰好和"前方安全"是同一种体验。所以这里必须主动出声 + 震动，绝不能只写日志。
     */
    fun onGuidanceInterrupted(noticeText: String) {
        if (!_uiState.value.isRunning) return

        logger.warning(TAG, "Guidance interrupted while running")
        _uiState.update { it.copy(wasInterrupted = true) }

        // 中断告知是这条链路上最该听见的一句，不能被半句场景描述盖住或接在后面。
        sceneDescriptionCoordinator.preemptForGuidance("guidance interrupted")

        // 震动先行：它不依赖 TTS 引擎，是最后一道能穿透的通知手段。
        hapticManager.play(GuidanceHaptic.INTERRUPTED)

        val state = _uiState.value
        if (!state.isSpeechEnabled) return
        if (state.isScreenReaderActive) {
            screenReaderAnnouncer.announce(noticeText)
        } else {
            // 必须说完：一条普通导航提示不能把"辅助已中断"冲掉。
            speechManager.speakSystemNotice(noticeText, priority = GuidanceNotice.INTERRUPTED_PRIORITY)
        }
    }

    fun acknowledgeInterruption() {
        // 回到前台时相机要重新绑定，第一帧之前的空档不是卡死：给它重新计启动宽限。
        if (_uiState.value.isRunning && stallWatchJob != null) stallDetector.start(clock())
        _uiState.update { it.copy(wasInterrupted = false) }
    }

    /**
     * 语音引擎彻底失效（多次重试后放弃）。
     *
     * 这条失效必须走非视觉通道，否则等于没告知：TTS 已经死了、说不出话；视觉卡片对盲人不存在；
     * 而"收不到任何提示"和"前方没有危险"在用户体验上是同一件事。
     *
     * 震动**不看 hapticsEnabled 开关**。理由：用户当初选的输出通道是语音，现在那条通道坏了，
     * 这一下震动不是导航提示，而是"应用无法工作"的一次性告知——它是仅剩的可达通道。
     * 只震一次，不会变成持续打扰。
     */
    private fun onSpeechEngineUnavailable() {
        val state = _uiState.value
        if (!state.isSpeechEnabled) return
        // 读屏在工作时我们根本不会初始化自带引擎，所以这个状态到不了这里；留一道判断只是
        // 为了让"读屏用户不会收到与他无关的告警"这件事显式成立。
        if (state.isScreenReaderActive) return

        logger.error(TAG, "Speech engine unavailable; alerting through haptics")
        hapticManager.play(GuidanceHaptic.SPEECH_UNAVAILABLE)
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
        analysisJob?.cancel()
        analysisJob = null
        overlayRenderRequestId++
        overlayRenderJob?.cancel()
        overlayRenderJob = null
        latestSceneResult = null
        stopSceneAnalysisUseCase()
        speechManager.stop()
        hapticManager.cancel()
        _uiState.update {
            it.copy(
                isRunning = false,
                isInitializing = false,
                isLoading = false,
                // 用户主动停止，中断告警自然作废。
                wasInterrupted = false,
                lastEvents = emptyList(),
                activeStatusEvent = null,
                lastAnnouncedEvent = null,
                segMask = null,
                maskSourceAgeMs = 0L,
                frameDisplayWidth = null,
                frameDisplayHeight = null,
                obstacleDetections = emptyList(),
                latestSceneDebugInfo = null,
            )
        }
    }

    override fun onCleared() {
        stopStallWatch()
        analysisJob?.cancel()
        analysisJob = null
        overlayRenderJob?.cancel()
        overlayRenderJob = null
        stopSceneAnalysisUseCase()
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
        releaseJob = releaseScope.launch {
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
