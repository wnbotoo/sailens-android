package com.friady.sailens.presentation.scene

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.Bitmap
import android.os.SystemClock
import com.friady.sailens.camera.ImageFrameProvider
import com.friady.sailens.domain.model.scene.SceneEvent
import com.friady.sailens.domain.model.scene.SceneResult
import com.friady.sailens.domain.model.trace.TraceReplayReport
import com.friady.sailens.domain.model.trace.TraceSessionDescriptor
import com.friady.sailens.domain.service.LogService
import com.friady.sailens.domain.service.TraceService
import com.friady.sailens.domain.usecase.scene.StartSceneAnalysisUseCase
import com.friady.sailens.domain.usecase.scene.StopSceneAnalysisUseCase
import com.friady.sailens.domain.usecase.trace.EvaluateTraceReplayBudgetUseCase
import com.friady.sailens.domain.usecase.trace.ListTraceSessionsUseCase
import com.friady.sailens.domain.usecase.trace.LoadLatestTraceReplayReportUseCase
import com.friady.sailens.domain.usecase.trace.LoadTraceReplayReportUseCase
import com.friady.sailens.presentation.device.HapticManager
import com.friady.sailens.presentation.device.SpeechManager
import com.friady.sailens.presentation.ext.visualizeSemanticClassesForAspect
import com.friady.sailens.presentation.ext.visualizeInstanceMasks
import com.friady.sailens.presentation.ext.visualizeForAspect
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SceneAnalysisViewModel"

class SceneAnalysisViewModel(
    private val imageFrameProvider: ImageFrameProvider,
    private val startSceneAnalysisUseCase: StartSceneAnalysisUseCase,
    private val stopSceneAnalysisUseCase: StopSceneAnalysisUseCase,
    private val hapticManager: HapticManager,
    private val speechManager: SpeechManager,
    private val logger: LogService,
    private val traceService: TraceService,
    private val listTraceSessionsUseCase: ListTraceSessionsUseCase,
    private val loadTraceReplayReportUseCase: LoadTraceReplayReportUseCase,
    private val loadLatestTraceReplayReportUseCase: LoadLatestTraceReplayReportUseCase,
    private val evaluateTraceReplayBudgetUseCase: EvaluateTraceReplayBudgetUseCase,
    private val sceneOverlayConfig: SceneOverlayConfig,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SceneAnalysisUiState(
            enabledOverlayModes = sceneOverlayConfig.enabledOverlayModes,
            overlayMode = sceneOverlayConfig.effectiveInitialMode,
        )
    )
    val uiState: StateFlow<SceneAnalysisUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<SceneAnalysisUiEffect>()
    val uiEffect: SharedFlow<SceneAnalysisUiEffect> = _uiEffect.asSharedFlow()

    private var analysisJob: Job? = null
    private var releaseJob: Job? = null
    private var overlayRenderJob: Job? = null
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var frameCount: Long = 0
    private var latestSceneResult: SceneResult? = null
    private var lastOverlayRenderTimeMs: Long = 0L

    // State machine to prevent concurrent release/analysis initialization
    @Volatile
    private var isReleasingResources = false

    init {
        speechManager.initialize {
            logger.debug(TAG, "SpeechManager initialized")
            _uiState.update { it.copy(isSpeechReady = true, errorMessage = null) }
        }
        refreshTraceSessions()
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
        _uiState.update { it.copy(isSpeechEnabled = enabled) }
        if (!enabled) {
            speechManager.stop()
        } else {
            speechManager.initialize {
                _uiState.update { state -> state.copy(isSpeechReady = true) }
            }
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isHapticsEnabled = enabled) }
        if (!enabled) {
            hapticManager.cancel()
        }
    }

    fun setOverlayMode(overlayMode: SceneOverlayMode) {
        lastOverlayRenderTimeMs = 0L
        val effectiveOverlayMode = sceneOverlayConfig.coerceEnabledMode(overlayMode)
        if (effectiveOverlayMode == SceneOverlayMode.OFF) {
            overlayRenderJob?.cancel()
            overlayRenderJob = null
            _uiState.update {
                it.copy(
                    overlayMode = SceneOverlayMode.OFF,
                    segMask = null,
                    trackedObstacles = emptyList(),
                    instanceDetections = emptyList(),
                )
            }
            return
        }

        val result = latestSceneResult
        _uiState.update {
            it.copy(
                overlayMode = effectiveOverlayMode,
                segMask = null,
                trackedObstacles = result?.trackedObstaclesForOverlay(effectiveOverlayMode).orEmpty(),
                instanceDetections = result?.instanceDetectionsForOverlay(effectiveOverlayMode).orEmpty(),
            )
        }
        scheduleOverlayRender(result, effectiveOverlayMode, force = true)
    }

    fun openTraceReplaySessionsScreen() {
        _uiState.update { it.copy(currentScreen = SceneAnalysisScreen.TRACE_REPLAY_SESSIONS) }
        refreshTraceSessions()
    }

    fun showLiveAnalysisScreen() {
        _uiState.update { it.copy(currentScreen = SceneAnalysisScreen.LIVE_ANALYSIS) }
    }

    fun showTraceReplaySessionsScreen() {
        _uiState.update { it.copy(currentScreen = SceneAnalysisScreen.TRACE_REPLAY_SESSIONS) }
    }

    fun refreshTraceSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTraceReplayLoading = true) }
            runCatching {
                withContext(Dispatchers.IO) {
                    listTraceSessionsUseCase()
                }
            }.onSuccess { sessions ->
                _uiState.update {
                    it.copy(
                        isTraceReplayLoading = false,
                        traceSessions = sessions,
                    )
                }
            }.onFailure { error ->
                logger.error(TAG, "Error refreshing trace sessions", error)
                _uiState.update { it.copy(isTraceReplayLoading = false) }
                _uiEffect.emit(SceneAnalysisUiEffect.ShowToast(error.message ?: "Failed to refresh traces"))
            }
        }
    }

    fun loadLatestTraceReplayReport() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTraceReplayLoading = true) }
            runCatching {
                val sessions = withContext(Dispatchers.IO) { listTraceSessionsUseCase() }
                val latestSessionId = sessions.firstOrNull()?.sessionId
                val report = withContext(Dispatchers.IO) { loadLatestTraceReplayReportUseCase() }
                Triple(sessions, latestSessionId, report)
            }.onSuccess { (sessions, latestSessionId, report) ->
                applyLoadedTraceReport(
                    sessions = sessions,
                    selectedSessionId = latestSessionId,
                    report = report,
                    currentScreen = if (report != null) {
                        SceneAnalysisScreen.TRACE_REPLAY_REPORT
                    } else {
                        SceneAnalysisScreen.TRACE_REPLAY_SESSIONS
                    },
                )
                if (report == null) {
                    _uiEffect.emit(SceneAnalysisUiEffect.ShowToast("No trace sessions available yet"))
                }
            }.onFailure { error ->
                logger.error(TAG, "Error loading latest trace replay report", error)
                _uiState.update { it.copy(isTraceReplayLoading = false) }
                _uiEffect.emit(SceneAnalysisUiEffect.ShowToast(error.message ?: "Failed to load latest trace report"))
            }
        }
    }

    fun loadTraceReplayReport(sessionId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentScreen = SceneAnalysisScreen.TRACE_REPLAY_REPORT,
                    isTraceReplayLoading = true,
                    selectedTraceSessionId = sessionId,
                )
            }
            runCatching {
                val sessions = withContext(Dispatchers.IO) { listTraceSessionsUseCase() }
                val report = withContext(Dispatchers.IO) { loadTraceReplayReportUseCase(sessionId) }
                sessions to report
            }.onSuccess { (sessions, report) ->
                applyLoadedTraceReport(
                    sessions = sessions,
                    selectedSessionId = sessionId,
                    report = report,
                    currentScreen = if (report != null) {
                        SceneAnalysisScreen.TRACE_REPLAY_REPORT
                    } else {
                        SceneAnalysisScreen.TRACE_REPLAY_SESSIONS
                    },
                )
                if (report == null) {
                    _uiEffect.emit(SceneAnalysisUiEffect.ShowToast("Trace report unavailable for session $sessionId"))
                }
            }.onFailure { error ->
                logger.error(TAG, "Error loading trace replay report", error)
                _uiState.update { it.copy(isTraceReplayLoading = false) }
                _uiEffect.emit(SceneAnalysisUiEffect.ShowToast(error.message ?: "Failed to load trace report"))
            }
        }
    }

    fun copyTraceReplaySummary() {
        val state = _uiState.value
        val report = state.traceReplayReport ?: return
        viewModelScope.launch {
            _uiEffect.emit(
                SceneAnalysisUiEffect.CopyToClipboard(
                    label = "trace_replay_report",
                    text = buildTraceReplaySummary(report, state.traceReplayWarnings),
                )
            )
        }
    }

    fun shareTraceReplayFile() {
        val sessionId = _uiState.value.traceReplayReport?.sessionId
            ?: _uiState.value.selectedTraceSessionId
            ?: return

        viewModelScope.launch {
            _uiEffect.emit(SceneAnalysisUiEffect.ShareTraceFile(sessionId))
        }
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
            startSceneAnalysisUseCase(imageFrameProvider.frames).onStart {
                _uiState.update {
                    it.copy(isInitializing = false, isRunning = true, isLoading = false)
                }
                logger.info(TAG, "Scene analysis started")
            }.catch { e ->
                logger.error(TAG, "Error in scene analysis", e)
                speechManager.stop()
                hapticManager.cancel()
                latestSceneResult = null
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        isLoading = false,
                        segMask = null,
                        trackedObstacles = emptyList(),
                        instanceDetections = emptyList(),
                        errorMessage = e.message ?: "Unknown error"
                    )
                }
                _uiEffect.emit(SceneAnalysisUiEffect.ShowToast(e.message ?: "Unknown error"))
            }.collectLatest { result ->
                frameCount++
                latestSceneResult = result
                val overlayMode = _uiState.value.overlayMode
                val events = result.events
                _uiState.update {
                    it.copy(
                        frameDisplayWidth = result.frameDisplayWidth,
                        frameDisplayHeight = result.frameDisplayHeight,
                        trackedObstacles = result.trackedObstaclesForOverlay(overlayMode),
                        instanceDetections = result.instanceDetectionsForOverlay(overlayMode),
                        latestSceneDebugInfo = result.debugInfo.takeIf { sceneOverlayConfig.enableDebugPanel },
                        lastEvents = events,
                        frameCount = frameCount,
                        eventCount = it.eventCount + events.size
                    )
                }
                scheduleOverlayRender(result, overlayMode)
                if (frameCount == 1L) {
                    logger.info(
                        TAG,
                        "First frame diagnostics",
                        mapOf(
                            "semanticProvider" to (result.debugInfo?.semanticProvider ?: "unknown"),
                            "instanceProvider" to (result.debugInfo?.instanceProvider ?: "unknown"),
                            "inferenceStrategy" to (result.debugInfo?.inferenceStrategy ?: "unknown"),
                            "trackedObstacles" to result.obstacles.size,
                            "rawInstances" to result.instanceDetections.size,
                            "rawInstanceMasks" to result.instanceDetections.count { it.mask != null },
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

        if (state.isSpeechEnabled) {
            speechManager.speak(primaryEvent)
        }

        if (state.isHapticsEnabled) {
            hapticManager.trigger(primaryEvent)
        }
    }

    private fun stopSceneAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
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
                segMask = null,
                frameDisplayWidth = null,
                frameDisplayHeight = null,
                trackedObstacles = emptyList(),
                instanceDetections = emptyList(),
                latestSceneDebugInfo = null,
            )
        }
    }

    override fun onCleared() {
        analysisJob?.cancel()
        analysisJob = null
        overlayRenderJob?.cancel()
        overlayRenderJob = null
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
            overlayRenderJob?.cancel()
            overlayRenderJob = null
            if (_uiState.value.segMask != null) {
                _uiState.update { it.copy(segMask = null) }
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastOverlayRenderTimeMs < sceneOverlayConfig.bitmapRenderIntervalMs) {
            return
        }
        lastOverlayRenderTimeMs = now

        overlayRenderJob?.cancel()
        overlayRenderJob = viewModelScope.launch {
            val renderStartAt = SystemClock.elapsedRealtime()
            val bitmap = withContext(Dispatchers.Default) {
                result.toOverlayBitmap(overlayMode)
            }
            val renderCompletedAt = SystemClock.elapsedRealtime()
            traceService.recordOverlayRender(
                renderedAt = renderCompletedAt,
                renderMs = renderCompletedAt - renderStartAt,
                overlayMode = overlayMode.name,
                bitmapRendered = bitmap != null,
            )
            if (_uiState.value.overlayMode == overlayMode) {
                _uiState.update { it.copy(segMask = bitmap) }
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

    private fun applyLoadedTraceReport(
        sessions: List<TraceSessionDescriptor>,
        selectedSessionId: String?,
        report: TraceReplayReport?,
        currentScreen: SceneAnalysisScreen,
    ) {
        val warnings = report?.let { evaluateTraceReplayBudgetUseCase(it).warnings }.orEmpty()
        _uiState.update {
            it.copy(
                currentScreen = currentScreen,
                isTraceReplayLoading = false,
                traceSessions = sessions,
                selectedTraceSessionId = selectedSessionId,
                traceReplayReport = report,
                traceReplayWarnings = warnings,
            )
        }
    }

    private fun buildTraceReplaySummary(
        report: TraceReplayReport,
        warnings: List<String>,
    ): String {
        val droppedRatePercent = (report.droppedFrameRate * 100).toInt()
        val blockedRatePercent = (report.blockedFrameRate * 100).toInt()
        val dangerRatePercent = (report.dangerousFrameRate * 100).toInt()
        val navigationPassablePercent = (report.avgNavigationPassableRatio * 100).toInt()
        val blockageConfidencePercent = (report.avgBlockageConfidence * 100).toInt()
        val verticalReachPercent = (report.avgVerticalReachRatio * 100).toInt()
        val floodReachPercent = (report.avgFloodReachRatio * 100).toInt()
        val widthRetentionPercent = (report.avgWidthRetentionP25 * 100).toInt()
        val warningSection = if (warnings.isEmpty()) {
            "budget=ok"
        } else {
            "warnings=${warnings.joinToString(separator = "; ")}"
        }

        return buildString {
            appendLine("session=${report.sessionId}")
            appendLine("pipelineMode=${report.pipelineMode ?: "unknown"}")
            appendLine("targetHardware=${report.targetHardwareProfile ?: "unknown"}")
            appendLine("frames=${report.totalFrames} observed=${report.totalObservedFrames} dropped=${report.droppedFrames} (${droppedRatePercent}%)")
            appendLine("events=${report.totalEvents} blocked=${blockedRatePercent}% danger=${dangerRatePercent}%")
            appendLine("fps=camera:${report.cameraInputFps} pipeline:${report.pipelineOutputFps} throughput:${report.pipelineThroughputFps}")
            appendLine("avgPipelineMs=${report.avgTotalPipelineMs} p95PipelineMs=${report.p95TotalPipelineMs}")
            appendLine("avgInferenceMs=${report.avgInferenceMs} errors=${report.errorCount}")
            appendLine("modelFps=sem:${report.semanticModelFps} seg:${report.instanceModelFps} runFps=sem:${report.semanticRunFps} seg:${report.instanceRunFps}")
            appendLine("maskRender=count:${report.maskRenderCount}/${report.overlayRenderCount} fps:${report.maskRenderFps} avgMs:${report.avgMaskRenderMs}")
            appendLine("semMs=pre:${report.avgSemanticPreprocessMs} infer:${report.avgSemanticInferenceMs} read:${report.avgSemanticOutputReadMs} post:${report.avgSemanticPostprocessMs}")
            appendLine("segMs=pre:${report.avgInstancePreprocessMs} infer:${report.avgInstanceInferenceMs} read:${report.avgInstanceOutputReadMs} post:${report.avgInstancePostprocessMs}")
            appendLine("navPassable=${navigationPassablePercent}% blockageConfidence=${blockageConfidencePercent}% verticalReach=${verticalReachPercent}% floodReach=${floodReachPercent}% widthRetentionP25=${widthRetentionPercent}%")
            appendLine("messageKeys=${report.uniqueMessageKeys.joinToString()}")
            append(warningSection)
        }
    }

    private fun SceneResult.trackedObstaclesForOverlay(overlayMode: SceneOverlayMode) =
        if (sceneOverlayConfig.isModeEnabled(overlayMode) && overlayMode == SceneOverlayMode.INSTANCE_DEBUG) {
            obstacles
        } else {
            emptyList()
        }

    private fun SceneResult.instanceDetectionsForOverlay(overlayMode: SceneOverlayMode) =
        if (!sceneOverlayConfig.isModeEnabled(overlayMode)) {
            emptyList()
        } else {
            when (overlayMode) {
                SceneOverlayMode.DETECTION_BOXES,
                SceneOverlayMode.INSTANCE_DEBUG -> instanceDetections
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
            SceneOverlayMode.PASSABLE_AREA_MASK -> passableMask?.visualizeForAspect(sourceAspectRatio)
            SceneOverlayMode.SEMANTIC_CLASS_MASK -> segmentationMask?.visualizeSemanticClassesForAspect(sourceAspectRatio)
            SceneOverlayMode.INSTANCE_DEBUG -> instanceDetections.visualizeInstanceMasks()
            SceneOverlayMode.OFF,
            SceneOverlayMode.DETECTION_BOXES -> null
        }
    }

    private fun SceneOverlayMode.rendersBitmap(): Boolean {
        return when (this) {
            SceneOverlayMode.PASSABLE_AREA_MASK,
            SceneOverlayMode.SEMANTIC_CLASS_MASK,
            SceneOverlayMode.INSTANCE_DEBUG -> true
            SceneOverlayMode.OFF,
            SceneOverlayMode.DETECTION_BOXES -> false
        }
    }
}
