package com.sailens.shell.guidance.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.Bitmap
import android.os.SystemClock
import com.sailens.camera.FrameSource
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneResult
import com.sailens.vlm.SceneDescriber
import com.sailens.vlm.SceneDescriptionChunk
import com.sailens.core.log.LogService
import com.sailens.guidance.service.TraceService
import com.sailens.describe.DescribeSceneUseCase
import com.sailens.guidance.usecase.scene.StartSceneAnalysisUseCase
import com.sailens.guidance.usecase.scene.StopSceneAnalysisUseCase
import com.sailens.shell.diagnostics.GuidanceDiagnosticsStore
import com.sailens.output.AccessibilityStatusProvider
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.shell.device.HapticManager
import com.sailens.shell.device.GuidanceAnnouncements
import com.sailens.shell.device.SceneEventTextResolver
import com.sailens.output.SpeechClauseBuffer
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
    private val describeSceneUseCase: DescribeSceneUseCase,
    private val sceneDescriber: SceneDescriber,
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
    private var releaseJob: Job? = null
    private var overlayRenderJob: Job? = null
    private var describeJob: Job? = null
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
        // 只问"有没有模型"，不加载。VLM 权重比 sem/det 大一个量级，冷启动时加载会明显拖慢
        // 进入引导的时间，而多数会话根本不会用到它——真正的加载推迟到用户第一次发问。
        _uiState.update { it.copy(isSceneDescriptionAvailable = sceneDescriber.isAvailable) }
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

            // collectLatest is often used for high-frequency data, discarding previous incomplete processing
            startSceneAnalysisUseCase(frameSource.frames).onStart {
                _uiState.update {
                    it.copy(isInitializing = false, isRunning = true, isLoading = false)
                }
                logger.info(TAG, "Scene analysis started")
            }.catch { e ->
                logger.error(TAG, "Error in scene analysis", e)
                speechManager.stop()
                hapticManager.cancel()
                latestSceneResult = null
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
                latestSceneResult = result
                val overlayMode = _uiState.value.overlayMode
                val events = result.events
                val latestSceneDebugInfo = result.debugInfo.takeIf {
                    sceneOverlayConfig.enableDebugPanel && guidanceDiagnosticsStore.state.value.showDiagnostics
                }
                _uiState.update {
                    it.withFrameEvents(events, System.currentTimeMillis()).copy(
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
                onSceneEvents(events)
            }
        }
    }

    private fun onSceneEvents(events: List<SceneEvent>) {
        if (events.isEmpty()) return
        val primaryEvent = events.first()
        val state = _uiState.value

        logger.debug(TAG, "Scene events generated, ${primaryEvent.messageKey}", mapOf("count" to events.size))

        if (state.hasGuidanceOutputChannel()) {
            _uiState.update { it.copy(lastAnnouncedEvent = primaryEvent) }
        }

        if (state.isSpeechEnabled) {
            // 两条播报通道互斥。屏幕阅读器在工作时由它来念——这样语速/音量/语言跟随用户
            // 在 TalkBack 里的既有设置，也避免同一句被念两遍。
            if (state.isScreenReaderActive) {
                val text = textResolver.resolve(primaryEvent.toSceneEventText())
                viewModelScope.launch {
                    _uiEffect.emit(SceneAnalysisUiEffect.Announce(text))
                }
            } else {
                speechManager.speak(announcements.announcementFor(primaryEvent))
            }
        }

        if (state.isHapticsEnabled) {
            hapticManager.trigger(primaryEvent)
        }
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

        // 震动先行：它不依赖 TTS 引擎，是最后一道能穿透的通知手段。
        hapticManager.play(GuidanceHaptic.INTERRUPTED)

        val state = _uiState.value
        if (!state.isSpeechEnabled) return
        if (state.isScreenReaderActive) {
            viewModelScope.launch {
                _uiEffect.emit(SceneAnalysisUiEffect.Announce(noticeText))
            }
        } else {
            speechManager.speakSystemNotice(noticeText)
        }
    }

    fun acknowledgeInterruption() {
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
            viewModelScope.launch {
                _uiEffect.emit(SceneAnalysisUiEffect.Announce(text))
            }
        } else {
            speechManager.speakSystemNotice(text)
        }
    }

    /**
     * 用户主动发问一次"我面前是什么"，由 VLM 回答。
     *
     * **目前没有任何调用方，这是刻意的。**没有可用的 VLM 之前不往屏幕上加入口：一个按下去
     * 不会回答的按钮，对盲人用户就是一次徒劳的焦点停留，而"按了没反应"和"前方没东西"在他
     * 那里是同一种体验。这条链路做到 ViewModel 为止，接 UI 等模型到位（届时入口应当由
     * [SceneAnalysisUiState.isSceneDescriptionAvailable] 把关，见 [canDescribeScene]）。
     *
     * 与自动播报是两回事，几个刻意的区别：
     * - **不过冷却。**冷却是为了防止自动播报刷屏；这一句是用户要来的。
     * - **可以被导航提示打断。**子句走 [SpeechManager.speakSystemNotice]（QUEUE_ADD）排队，
     *   而场景事件走 `speak()`（QUEUE_FLUSH）。所以描述念到一半撞上障碍告警时，告警会把剩下的
     *   描述冲掉——这个优先级正是我们要的："别撞上"永远压过"那是什么"。
     * - **边解码边念。**VLM 一次推理数秒，等整段出齐再开口就是数秒静默，而用户看不到进度。
     *   [SpeechClauseBuffer] 把 token 流攒成子句，出一句念一句。
     *
     * @param failureNotice 失败时说出来的话，由调用方给出本地化文本（同
     *   [onGuidanceInterrupted] 的约定：ViewModel 不碰资源）。用户已经主动发问，静默失败在他
     *   那里和"前方什么都没有"是同一种体验，所以这条链路上的失败必须出声，不能只写日志。
     */
    fun describeScene(failureNotice: String) {
        val state = _uiState.value
        if (!state.canDescribeScene()) {
            logger.debug(
                TAG,
                "Scene description request ignored",
                mapOf(
                    "available" to state.isSceneDescriptionAvailable,
                    "inFlight" to state.isDescribingScene,
                    "speechChannel" to state.hasSpeechOutputChannel(),
                ),
            )
            return
        }

        describeJob?.cancel()
        describeJob = viewModelScope.launch {
            _uiState.update { it.copy(isDescribingScene = true) }
            // 读屏在工作时不逐句播：announceForAccessibility 会打断上一条未念完的公告，
            // 流式喂给它只会得到一串互相截断的碎句。那条通道整段说完再交出去。
            //
            // 这里**刻意只读一次**，而不是每片都重新取：一次描述必须整段走同一条通道。生成中途
            // 用户开关了 TalkBack 的话，半句走自带 TTS、半句走读屏，得到的是两个引擎念出来的
            // 一句被劈开的话。下一次发问自然会用新通道。
            val screenReaderActive = state.isScreenReaderActive
            val clauseBuffer = SpeechClauseBuffer()
            var spokeAnyClause = false
            try {
                describeSceneUseCase().collect { chunk ->
                    when (chunk) {
                        is SceneDescriptionChunk.Delta -> {
                            if (screenReaderActive) return@collect
                            clauseBuffer.append(chunk.text)?.let { clause ->
                                speechManager.speakSystemNotice(clause)
                                spokeAnyClause = true
                            }
                        }

                        is SceneDescriptionChunk.Completed -> {
                            val description = chunk.description
                            _uiState.update { it.copy(lastSceneDescription = description.text) }
                            logger.info(
                                TAG,
                                "Scene description completed",
                                mapOf(
                                    "timeToFirstTokenMs" to description.timeToFirstTokenMs,
                                    "latencyMs" to description.latencyMs,
                                    "backend" to description.backend,
                                    "streamed" to spokeAnyClause,
                                ),
                            )
                            if (description.text.isBlank()) return@collect

                            if (screenReaderActive) {
                                _uiEffect.emit(SceneAnalysisUiEffect.Announce(description.text))
                                return@collect
                            }
                            clauseBuffer.drain()?.let { tail ->
                                speechManager.speakSystemNotice(tail)
                                spokeAnyClause = true
                            }
                            // 一个 Delta 都没来过：runtime 不支持流式，整段文本只在这里出现一次。
                            if (!spokeAnyClause) {
                                speechManager.speakSystemNotice(description.text)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(TAG, "Scene description failed", e)
                announceThroughSpeechChannel(failureNotice)
            } finally {
                _uiState.update { it.copy(isDescribingScene = false) }
            }
        }
    }

    /**
     * 把一句话送上当前生效的那条语音通道。两条通道互斥，见 [applyScreenReaderMode]。
     */
    private suspend fun announceThroughSpeechChannel(text: String) {
        if (_uiState.value.isScreenReaderActive) {
            _uiEffect.emit(SceneAnalysisUiEffect.Announce(text))
        } else {
            speechManager.speakSystemNotice(text)
        }
    }

    private fun stopSceneAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        overlayRenderRequestId++
        overlayRenderJob?.cancel()
        overlayRenderJob = null
        // 正在解码的描述跟着会话一起结束：用户已经停了引导，几秒后再冒出一句场景描述
        // 只会让他以为辅助还在跑。
        describeJob?.cancel()
        describeJob = null
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
                isDescribingScene = false,
                lastSceneDescription = null,
            )
        }
    }

    override fun onCleared() {
        analysisJob?.cancel()
        analysisJob = null
        overlayRenderJob?.cancel()
        overlayRenderJob = null
        describeJob?.cancel()
        describeJob = null
        stopSceneAnalysisUseCase()
        speechManager.stop()
        hapticManager.cancel()
        releaseSceneAnalysisResources()
        speechManager.release()
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
                    // VLM 是懒加载的，多数会话根本没加载过——release() 那时是个空操作。
                    // 但一旦加载过，它占的显存比 sem/det 都大，不能跟着进程一起漏到下一次。
                    runCatching {
                        sceneDescriber.release()
                    }.onFailure { error ->
                        logger.error(TAG, "Error releasing VLM", error)
                    }
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
