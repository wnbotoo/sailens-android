package com.friady.sailens.presentation.scene

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.friady.sailens.camera.ImageFrameProvider
import com.friady.sailens.domain.model.scene.SceneEvent
import com.friady.sailens.domain.model.trace.TraceReplayReport
import com.friady.sailens.domain.model.trace.TraceSessionDescriptor
import com.friady.sailens.domain.service.LogService
import com.friady.sailens.domain.usecase.scene.StartSceneAnalysisUseCase
import com.friady.sailens.domain.usecase.scene.StopSceneAnalysisUseCase
import com.friady.sailens.domain.usecase.trace.EvaluateTraceReplayBudgetUseCase
import com.friady.sailens.domain.usecase.trace.ListTraceSessionsUseCase
import com.friady.sailens.domain.usecase.trace.LoadLatestTraceReplayReportUseCase
import com.friady.sailens.domain.usecase.trace.LoadTraceReplayReportUseCase
import com.friady.sailens.presentation.device.HapticManager
import com.friady.sailens.presentation.device.SpeechManager
import com.friady.sailens.presentation.ext.visualize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val TAG = "SceneAnalysisViewModel"

class SceneAnalysisViewModel(
    private val imageFrameProvider: ImageFrameProvider,
    private val startSceneAnalysisUseCase: StartSceneAnalysisUseCase,
    private val stopSceneAnalysisUseCase: StopSceneAnalysisUseCase,
    private val hapticManager: HapticManager,
    private val speechManager: SpeechManager,
    private val logger: LogService,
    private val listTraceSessionsUseCase: ListTraceSessionsUseCase,
    private val loadTraceReplayReportUseCase: LoadTraceReplayReportUseCase,
    private val loadLatestTraceReplayReportUseCase: LoadLatestTraceReplayReportUseCase,
    private val evaluateTraceReplayBudgetUseCase: EvaluateTraceReplayBudgetUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SceneAnalysisUiState())
    val uiState: StateFlow<SceneAnalysisUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<SceneAnalysisUiEffect>()
    val uiEffect: SharedFlow<SceneAnalysisUiEffect> = _uiEffect.asSharedFlow()

    private var analysisJob: Job? = null
    private var frameCount: Long = 0

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

    private fun startSceneAnalysis() {
        frameCount = 0
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
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
                _uiState.update {
                    it.copy(
                        isRunning = false, isLoading = false, errorMessage = e.message ?: "Unknown error"
                    )
                }
                _uiEffect.emit(SceneAnalysisUiEffect.ShowToast(e.message ?: "Unknown error"))
            }.collectLatest { result ->
                frameCount++
                val mask = result.passableMask?.visualize()
                val events = result.events
                _uiState.update {
                    it.copy(
                        segMask = mask,
                        lastEvents = events,
                        frameCount = frameCount,
                        eventCount = it.eventCount + events.size
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
        stopSceneAnalysisUseCase()
        speechManager.stop()
        hapticManager.cancel()
        _uiState.update {
            it.copy(isRunning = false, isInitializing = false, isLoading = false, segMask = null)
        }
    }

    override fun onCleared() {
        analysisJob?.cancel()
        analysisJob = null
        stopSceneAnalysisUseCase()
        speechManager.stop()
        hapticManager.cancel()
        runBlocking {
            runCatching {
                stopSceneAnalysisUseCase.release()
            }.onFailure { error ->
                logger.error(TAG, "Error releasing scene analysis resources", error)
            }
        }
        speechManager.release()
        super.onCleared()
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
        val droppedRatePercent = if (report.totalFrames > 0) {
            (report.droppedFrames.toDouble() / report.totalFrames * 100).toInt()
        } else {
            0
        }
        val blockedRatePercent = (report.blockedFrameRate * 100).toInt()
        val dangerRatePercent = (report.dangerousFrameRate * 100).toInt()
        val warningSection = if (warnings.isEmpty()) {
            "budget=ok"
        } else {
            "warnings=${warnings.joinToString(separator = "; ")}"
        }

        return buildString {
            appendLine("session=${report.sessionId}")
            appendLine("pipelineMode=${report.pipelineMode ?: "unknown"}")
            appendLine("targetHardware=${report.targetHardwareProfile ?: "unknown"}")
            appendLine("frames=${report.totalFrames} dropped=${report.droppedFrames} (${droppedRatePercent}%)")
            appendLine("events=${report.totalEvents} blocked=${blockedRatePercent}% danger=${dangerRatePercent}%")
            appendLine("avgPipelineMs=${report.avgTotalPipelineMs} p95PipelineMs=${report.p95TotalPipelineMs}")
            appendLine("avgInferenceMs=${report.avgInferenceMs} errors=${report.errorCount}")
            appendLine("messageKeys=${report.uniqueMessageKeys.joinToString()}")
            append(warningSection)
        }
    }
}
