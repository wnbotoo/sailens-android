package com.sailens.shell.trace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sailens.guidance.model.trace.TraceReplayReport
import com.sailens.guidance.model.trace.TraceSessionDescriptor
import com.sailens.core.log.LogService
import com.sailens.guidance.usecase.trace.EvaluateTraceReplayBudgetUseCase
import com.sailens.guidance.usecase.trace.ListTraceSessionsUseCase
import com.sailens.guidance.usecase.trace.LoadLatestTraceReplayReportUseCase
import com.sailens.guidance.usecase.trace.LoadTraceReplayReportUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TraceReplayViewModel"

data class TraceReplayUiState(
    val isLoading: Boolean = false,
    val traceSessions: List<TraceSessionDescriptor> = emptyList(),
    val selectedTraceSessionId: String? = null,
    val traceReplayReport: TraceReplayReport? = null,
    val traceReplayWarnings: List<String> = emptyList(),
)

sealed interface TraceReplayUiEffect {
    data class ShowToast(val message: String) : TraceReplayUiEffect
    data class CopyToClipboard(val label: String, val text: String) : TraceReplayUiEffect
    data class ShareTraceFile(val sessionId: String) : TraceReplayUiEffect
}

/**
 * Debug-only ViewModel for the trace-replay diagnostics screens. Lives in `src/debug` so it is
 * physically excluded from the release variant. Each Nav3 entry gets its own instance (per-entry
 * ViewModel scoping), so the sessions list and a report load independently.
 */
class TraceReplayViewModel(
    private val logger: LogService,
    private val listTraceSessionsUseCase: ListTraceSessionsUseCase,
    private val loadTraceReplayReportUseCase: LoadTraceReplayReportUseCase,
    private val loadLatestTraceReplayReportUseCase: LoadLatestTraceReplayReportUseCase,
    private val evaluateTraceReplayBudgetUseCase: EvaluateTraceReplayBudgetUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TraceReplayUiState())
    val uiState: StateFlow<TraceReplayUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<TraceReplayUiEffect>()
    val uiEffect: SharedFlow<TraceReplayUiEffect> = _uiEffect.asSharedFlow()

    fun refreshTraceSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                withContext(Dispatchers.IO) { listTraceSessionsUseCase() }
            }.onSuccess { sessions ->
                _uiState.update { it.copy(isLoading = false, traceSessions = sessions) }
            }.onFailure { error ->
                logger.error(TAG, "Error refreshing trace sessions", error)
                _uiState.update { it.copy(isLoading = false) }
                _uiEffect.emit(TraceReplayUiEffect.ShowToast(error.message ?: "Failed to refresh traces"))
            }
        }
    }

    /** Loads a report; pass null to load the latest available session. */
    fun loadReport(sessionId: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedTraceSessionId = sessionId) }
            runCatching {
                val sessions = withContext(Dispatchers.IO) { listTraceSessionsUseCase() }
                val report = withContext(Dispatchers.IO) {
                    if (sessionId == null) {
                        loadLatestTraceReplayReportUseCase()
                    } else {
                        loadTraceReplayReportUseCase(sessionId)
                    }
                }
                sessions to report
            }.onSuccess { (sessions, report) ->
                val warnings = report?.let { evaluateTraceReplayBudgetUseCase(it).warnings }.orEmpty()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        traceSessions = sessions,
                        selectedTraceSessionId = report?.sessionId ?: sessionId,
                        traceReplayReport = report,
                        traceReplayWarnings = warnings,
                    )
                }
                if (report == null) {
                    _uiEffect.emit(TraceReplayUiEffect.ShowToast("No trace report available"))
                }
            }.onFailure { error ->
                logger.error(TAG, "Error loading trace replay report", error)
                _uiState.update { it.copy(isLoading = false) }
                _uiEffect.emit(TraceReplayUiEffect.ShowToast(error.message ?: "Failed to load trace report"))
            }
        }
    }

    fun copyTraceReplaySummary() {
        val state = _uiState.value
        val report = state.traceReplayReport ?: return
        viewModelScope.launch {
            _uiEffect.emit(
                TraceReplayUiEffect.CopyToClipboard(
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
            _uiEffect.emit(TraceReplayUiEffect.ShareTraceFile(sessionId))
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
        val navigationPassableDeltaPercent = (report.avgNavigationPassableDelta * 100).toInt()
        val maxNavigationPassableDeltaPercent = (report.maxNavigationPassableDelta * 100).toInt()
        val roadPercent = (report.avgRoadRatio * 100).toInt()
        val roadDeltaPercent = (report.avgRoadRatioDelta * 100).toInt()
        val maxRoadDeltaPercent = (report.maxRoadRatioDelta * 100).toInt()
        val blockageConfidencePercent = (report.avgBlockageConfidence * 100).toInt()
        val blockageConfidenceDeltaPercent = (report.avgBlockageConfidenceDelta * 100).toInt()
        val maxBlockageConfidenceDeltaPercent = (report.maxBlockageConfidenceDelta * 100).toInt()
        val verticalReachPercent = (report.avgVerticalReachRatio * 100).toInt()
        val verticalReachDeltaPercent = (report.avgVerticalReachDelta * 100).toInt()
        val floodReachPercent = (report.avgFloodReachRatio * 100).toInt()
        val floodReachDeltaPercent = (report.avgFloodReachDelta * 100).toInt()
        val widthRetentionPercent = (report.avgWidthRetentionP25 * 100).toInt()
        val widthRetentionDeltaPercent = (report.avgWidthRetentionP25Delta * 100).toInt()
        val occludedPassablePercent = (report.avgOccludedPassableRatio * 100).toInt()
        val maxOccludedPassablePercent = (report.maxOccludedPassableRatio * 100).toInt()
        val occludedPassableDeltaPercent = (report.avgOccludedPassableDelta * 100).toInt()
        val avgRoadVehicleConfidencePercent = (report.avgRoadVehicleConfidence * 100).toInt()
        val avgRoadVehicleBottomYPercent = (report.avgRoadVehicleBottomY * 100).toInt()
        val avgRoadVehicleCenterBandOverlapPercent = (report.avgRoadVehicleCenterBandOverlap * 100).toInt()
        val avgRoadVehicleAreaPercent = (report.avgRoadVehicleAreaRatio * 100).toInt()
        val semanticBackendSummary = buildBackendSummary(
            accelerators = report.semanticAccelerators,
            acceleratorSelections = report.semanticAcceleratorSelections,
            preprocessBackends = report.semanticPreprocessBackends,
            postprocessBackends = report.semanticPostprocessBackends,
        )
        val obstacleBackendSummary = buildBackendSummary(
            accelerators = report.obstacleAccelerators,
            acceleratorSelections = report.obstacleAcceleratorSelections,
            preprocessBackends = report.obstaclePreprocessBackends,
            postprocessBackends = report.obstaclePostprocessBackends,
        )
        val warningSection = if (warnings.isEmpty()) {
            "budget=ok"
        } else {
            "warnings=${warnings.joinToString(separator = "; ")}"
        }
        val dominantClasses = report.dominantClassPercentages
            .ifEmpty { report.dominantClasses }
            .ifEmpty { listOf("unknown") }
            .joinToString()
        val rawObstacleClasses = report.rawObstacleClassPercentages
            .ifEmpty { listOf("unknown") }
            .joinToString()
        val trackedObstacleCategories = report.trackedObstacleCategoryPercentages
            .ifEmpty { listOf("unknown") }
            .joinToString()
        val roadVehicleSources = report.roadVehicleSourcePercentages
            .ifEmpty { listOf("unknown") }
            .joinToString()
        val roadVehicleReasons = report.roadVehicleReasonPercentages
            .ifEmpty { listOf("unknown") }
            .joinToString()
        val blockageReasons = report.blockageReasonPercentages
            .ifEmpty { listOf("unknown") }
            .joinToString()

        return buildString {
            appendLine("session=${report.sessionId}")
            appendLine("pipelineMode=${report.pipelineMode ?: "unknown"}")
            appendLine("targetHardware=${report.targetHardwareProfile ?: "unknown"}")
            appendLine("frames=${report.totalFrames} observed=${report.totalObservedFrames} dropped=${report.droppedFrames} (${droppedRatePercent}%)")
            appendLine("events=${report.totalEvents} blocked=${blockedRatePercent}% danger=${dangerRatePercent}%")
            appendLine("fps=camera:${report.cameraInputFps} pipeline:${report.pipelineOutputFps} throughput:${report.pipelineThroughputFps}")
            appendLine("avgPipelineMs=${report.avgTotalPipelineMs} p95PipelineMs=${report.p95TotalPipelineMs}")
            appendLine("perceptionMs=avg:${report.avgInferenceMs} process:${report.avgProcessFrameMs} errors=${report.errorCount}")
            appendLine("logicMs=analyze:${report.avgAnalyzeSceneMs} decide:${report.avgDecideEventsMs} total:${report.avgLogicMs}")
            appendLine("runFps=sem:${report.semanticRunFps} obs:${report.obstacleRunFps} runs=sem:${report.semanticRunCount} obs:${report.obstacleRunCount}")
            appendLine("backend=sem:$semanticBackendSummary obs:$obstacleBackendSummary")
            appendLine("maskRender=count:${report.maskRenderCount}/${report.overlayRenderCount} fps:${report.maskRenderFps} avgMs:${report.avgMaskRenderMs} sourceAgeAvg:${report.avgMaskSourceAgeMs} sourceAgeMax:${report.maxMaskSourceAgeMs}")
            appendLine("semMs=total:${report.avgSemanticTotalMs} pre:${report.avgSemanticPreprocessMs} infer:${report.avgSemanticInferenceMs} read:${report.avgSemanticOutputReadMs} post:${report.avgSemanticPostprocessMs}")
            appendLine("obsMs=total:${report.avgObstacleTotalMs} pre:${report.avgObstaclePreprocessMs} infer:${report.avgObstacleInferenceMs} read:${report.avgObstacleOutputReadMs} post:${report.avgObstaclePostprocessMs}")
            appendLine("navPassable=${navigationPassablePercent}% road=${roadPercent}% blockageConfidence=${blockageConfidencePercent}% verticalReach=${verticalReachPercent}% floodReach=${floodReachPercent}% widthRetentionP25=${widthRetentionPercent}% occludedPassable=${occludedPassablePercent}% maxOccluded=${maxOccludedPassablePercent}%")
            appendLine("stabilityDelta=navAvg:${navigationPassableDeltaPercent}% navMax:${maxNavigationPassableDeltaPercent}% roadAvg:${roadDeltaPercent}% roadMax:${maxRoadDeltaPercent}% blockAvg:${blockageConfidenceDeltaPercent}% blockMax:${maxBlockageConfidenceDeltaPercent}% verticalAvg:${verticalReachDeltaPercent}% floodAvg:${floodReachDeltaPercent}% widthAvg:${widthRetentionDeltaPercent}% occludedAvg:${occludedPassableDeltaPercent}%")
            appendLine("obstacleStability=rawRunAvg:${report.avgRawObstacleDetectionCount} rawRunDeltaAvg:${report.avgRawObstacleDetectionCountDelta} rawRunDeltaMax:${report.maxRawObstacleDetectionCountDelta} trackedDeltaAvg:${report.avgObstacleCountDelta} trackedDeltaMax:${report.maxObstacleCountDelta}")
            appendLine("dominantClasses=$dominantClasses")
            appendLine("rawObstacleClasses=$rawObstacleClasses")
            appendLine("trackedObstacleCategories=$trackedObstacleCategories")
            appendLine("roadVehicleSources=$roadVehicleSources avgConfidence=${avgRoadVehicleConfidencePercent}%")
            appendLine("roadVehicleGeometry=reasons:$roadVehicleReasons bottomY:${avgRoadVehicleBottomYPercent}% centerOverlap:${avgRoadVehicleCenterBandOverlapPercent}% area:${avgRoadVehicleAreaPercent}%")
            appendLine("blockageReasons=$blockageReasons")
            appendLine("messageKeys=${report.uniqueMessageKeys.joinToString()}")
            append(warningSection)
        }
    }

    private fun buildBackendSummary(
        accelerators: List<String>,
        acceleratorSelections: List<String>,
        preprocessBackends: List<String>,
        postprocessBackends: List<String>,
    ): String {
        fun List<String>.summary() = ifEmpty { listOf("unknown") }.joinToString("|")
        return "accel=${accelerators.summary()},sel=${acceleratorSelections.summary()},pre=${preprocessBackends.summary()},post=${postprocessBackends.summary()}"
    }
}
