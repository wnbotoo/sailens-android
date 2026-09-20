package com.sailens.shell.trace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sailens.domain.model.trace.TraceReplayReport
import com.sailens.domain.model.trace.TraceSessionDescriptor
import com.sailens.shell.R
import com.sailens.shell.design.components.SailensScaffold
import com.sailens.shell.design.theme.SailensDimens
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TraceSessionsScreen(
    onNavigateBack: () -> Unit,
    onOpenReport: (sessionId: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TraceReplayViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val copiedMessage = stringResource(R.string.trace_report_copied)
    val fileMissingMessage = stringResource(R.string.trace_file_not_found)

    LaunchedEffect(Unit) { viewModel.refreshTraceSessions() }
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            handleTraceEffect(context, effect, copiedMessage, fileMissingMessage)
        }
    }

    SailensScaffold(
        title = stringResource(R.string.title_trace_sessions_page),
        onNavigateBack = onNavigateBack,
        navigateBackContentDescription = stringResource(R.string.cd_navigate_back),
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(SailensDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceMd),
        ) {
            Text(
                text = stringResource(R.string.msg_trace_sessions_page_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { onOpenReport(null) },
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_load_latest_trace_report))
            }
            OutlinedButton(
                onClick = { viewModel.refreshTraceSessions() },
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_refresh_trace_sessions))
            }

            if (state.isLoading) {
                LoadingRow(stringResource(R.string.msg_loading_trace_sessions))
            }

            if (state.traceSessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.msg_no_trace_sessions),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceMd),
                ) {
                    items(state.traceSessions, key = { it.sessionId }) { session ->
                        TraceSessionCard(
                            session = session,
                            isSelected = session.sessionId == state.selectedTraceSessionId,
                            onLoadClick = { onOpenReport(session.sessionId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TraceReportScreen(
    sessionId: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TraceReplayViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val copiedMessage = stringResource(R.string.trace_report_copied)
    val fileMissingMessage = stringResource(R.string.trace_file_not_found)

    LaunchedEffect(sessionId) { viewModel.loadReport(sessionId) }
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            handleTraceEffect(context, effect, copiedMessage, fileMissingMessage)
        }
    }

    SailensScaffold(
        title = stringResource(R.string.title_trace_report_page),
        onNavigateBack = onNavigateBack,
        navigateBackContentDescription = stringResource(R.string.cd_navigate_back),
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(SailensDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceMd),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm)) {
                Button(
                    onClick = viewModel::copyTraceReplaySummary,
                    enabled = state.traceReplayReport != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.btn_copy_trace_report))
                }
                Button(
                    onClick = viewModel::shareTraceReplayFile,
                    enabled = state.traceReplayReport != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.btn_share_trace_file))
                }
            }

            if (state.isLoading) {
                LoadingRow(stringResource(R.string.msg_loading_trace_report))
            }

            val report = state.traceReplayReport
            if (report == null) {
                Text(
                    text = stringResource(R.string.msg_no_trace_report),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                TraceReplayReportDetail(report = report, warnings = state.traceReplayWarnings)
            }
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

@Composable
private fun TraceSessionCard(
    session: TraceSessionDescriptor,
    isSelected: Boolean,
    onLoadClick: () -> Unit,
) {
    val title = if (isSelected) {
        stringResource(R.string.trace_session_selected_label, session.sessionId.takeLast(8))
    } else {
        stringResource(R.string.trace_session_button_label, session.sessionId.takeLast(8))
    }
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SailensDimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.trace_session_file_name, session.fileName),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.trace_session_last_modified,
                    formatTimestamp(session.lastModifiedAt),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onLoadClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_open_trace_report))
            }
        }
    }
}

@Composable
private fun TraceReplayReportDetail(
    report: TraceReplayReport,
    warnings: List<String>,
) {
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
    val maxVerticalReachDeltaPercent = (report.maxVerticalReachDelta * 100).toInt()
    val floodReachPercent = (report.avgFloodReachRatio * 100).toInt()
    val floodReachDeltaPercent = (report.avgFloodReachDelta * 100).toInt()
    val maxFloodReachDeltaPercent = (report.maxFloodReachDelta * 100).toInt()
    val widthRetentionPercent = (report.avgWidthRetentionP25 * 100).toInt()
    val widthRetentionDeltaPercent = (report.avgWidthRetentionP25Delta * 100).toInt()
    val maxWidthRetentionDeltaPercent = (report.maxWidthRetentionP25Delta * 100).toInt()
    val occludedPassablePercent = (report.avgOccludedPassableRatio * 100).toInt()
    val maxOccludedPassablePercent = (report.maxOccludedPassableRatio * 100).toInt()
    val occludedPassableDeltaPercent = (report.avgOccludedPassableDelta * 100).toInt()
    val maxOccludedPassableDeltaPercent = (report.maxOccludedPassableDelta * 100).toInt()
    val avgRoadVehicleConfidencePercent = (report.avgRoadVehicleConfidence * 100).toInt()
    val avgRoadVehicleBottomYPercent = (report.avgRoadVehicleBottomY * 100).toInt()
    val avgRoadVehicleCenterBandOverlapPercent = (report.avgRoadVehicleCenterBandOverlap * 100).toInt()
    val avgRoadVehicleAreaPercent = (report.avgRoadVehicleAreaRatio * 100).toInt()
    val messageKeys = report.uniqueMessageKeys.ifEmpty {
        listOf(stringResource(R.string.value_unknown))
    }.joinToString()
    val dominantClasses = report.dominantClassPercentages
        .ifEmpty { report.dominantClasses }
        .ifEmpty { listOf(stringResource(R.string.value_unknown)) }
        .joinToString()
    val rawObstacleClasses = report.rawObstacleClassPercentages.ifEmpty {
        listOf(stringResource(R.string.value_unknown))
    }.joinToString()
    val trackedObstacleCategories = report.trackedObstacleCategoryPercentages.ifEmpty {
        listOf(stringResource(R.string.value_unknown))
    }.joinToString()
    val roadVehicleSources = report.roadVehicleSourcePercentages.ifEmpty {
        listOf(stringResource(R.string.value_unknown))
    }.joinToString()
    val roadVehicleReasons = report.roadVehicleReasonPercentages.ifEmpty {
        listOf(stringResource(R.string.value_unknown))
    }.joinToString()
    val blockageReasons = report.blockageReasonPercentages.ifEmpty {
        listOf(stringResource(R.string.value_unknown))
    }.joinToString()
    val unknownValue = stringResource(R.string.value_unknown)
    val semanticAccelerators = report.semanticAccelerators.displayOrUnknown(unknownValue)
    val semanticPreprocessBackends = report.semanticPreprocessBackends.displayOrUnknown(unknownValue)
    val semanticPostprocessBackends = report.semanticPostprocessBackends.displayOrUnknown(unknownValue)
    val obstacleAccelerators = report.obstacleAccelerators.displayOrUnknown(unknownValue)
    val obstaclePreprocessBackends = report.obstaclePreprocessBackends.displayOrUnknown(unknownValue)
    val obstaclePostprocessBackends = report.obstaclePostprocessBackends.displayOrUnknown(unknownValue)

    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SailensDimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm),
        ) {
            TraceMetricLine(stringResource(R.string.trace_report_session_id, report.sessionId))
            TraceMetricLine(stringResource(R.string.trace_report_pipeline_mode, report.pipelineMode ?: unknownValue))
            TraceMetricLine(stringResource(R.string.trace_report_target_hardware, report.targetHardwareProfile ?: unknownValue))
            TraceMetricLine(stringResource(R.string.trace_report_frames, report.totalFrames))
            TraceMetricLine(stringResource(R.string.trace_report_observed_frames, report.totalObservedFrames))
            TraceMetricLine(stringResource(R.string.trace_report_dropped, report.droppedFrames, droppedRatePercent))
            TraceMetricLine(stringResource(R.string.trace_report_duration, report.durationMs))
            TraceMetricLine(stringResource(R.string.trace_report_camera_fps, report.cameraInputFps))
            TraceMetricLine(stringResource(R.string.trace_report_pipeline_fps, report.pipelineOutputFps))
            TraceMetricLine(stringResource(R.string.trace_report_throughput_fps, report.pipelineThroughputFps))
            TraceMetricLine(stringResource(R.string.trace_report_avg_pipeline, report.avgTotalPipelineMs))
            TraceMetricLine(stringResource(R.string.trace_report_p95_pipeline, report.p95TotalPipelineMs))
            TraceMetricLine(stringResource(R.string.trace_report_avg_inference, report.avgInferenceMs))
            TraceMetricLine(
                stringResource(
                    R.string.trace_report_logic_ms,
                    report.avgAnalyzeSceneMs,
                    report.avgDecideEventsMs,
                    report.avgLogicMs,
                )
            )
            TraceMetricLine(stringResource(R.string.trace_report_semantic_runs, report.semanticRunCount, report.semanticRunFps))
            TraceMetricLine(
                stringResource(
                    R.string.trace_report_semantic_stage_ms,
                    report.avgSemanticTotalMs,
                    report.avgSemanticPreprocessMs,
                    report.avgSemanticInferenceMs,
                    report.avgSemanticOutputReadMs,
                    report.avgSemanticPostprocessMs,
                )
            )
            TraceMetricLine(stringResource(R.string.trace_report_obstacle_runs, report.obstacleRunCount, report.obstacleRunFps))
            TraceMetricLine(
                stringResource(
                    R.string.trace_report_obstacle_stage_ms,
                    report.avgObstacleTotalMs,
                    report.avgObstaclePreprocessMs,
                    report.avgObstacleInferenceMs,
                    report.avgObstacleOutputReadMs,
                    report.avgObstaclePostprocessMs,
                )
            )
            TraceMetricLine(
                stringResource(
                    R.string.trace_report_semantic_backend,
                    semanticAccelerators,
                    semanticPreprocessBackends,
                    semanticPostprocessBackends,
                )
            )
            TraceMetricLine(
                stringResource(
                    R.string.trace_report_obstacle_backend,
                    obstacleAccelerators,
                    obstaclePreprocessBackends,
                    obstaclePostprocessBackends,
                )
            )
            TraceMetricLine(
                stringResource(
                    R.string.trace_report_mask_render,
                    report.maskRenderCount,
                    report.overlayRenderCount,
                    report.maskRenderFps,
                    report.avgMaskRenderMs,
                    report.avgMaskSourceAgeMs,
                    report.maxMaskSourceAgeMs,
                )
            )
            TraceMetricLine(stringResource(R.string.trace_report_errors, report.errorCount))
            TraceMetricLine(stringResource(R.string.trace_report_blocked_rate, blockedRatePercent))
            TraceMetricLine(stringResource(R.string.trace_report_danger_rate, dangerRatePercent))
            TraceMetricLine(stringResource(R.string.trace_report_avg_navigation_passable, navigationPassablePercent))
            TraceMetricLine(stringResource(R.string.trace_report_avg_road_ratio, roadPercent))
            TraceMetricLine(stringResource(R.string.trace_report_avg_blockage_confidence, blockageConfidencePercent))
            TraceMetricLine(stringResource(R.string.trace_report_avg_vertical_reach, verticalReachPercent))
            TraceMetricLine(stringResource(R.string.trace_report_avg_flood_reach, floodReachPercent))
            TraceMetricLine(stringResource(R.string.trace_report_avg_width_retention, widthRetentionPercent))
            TraceMetricLine(
                stringResource(
                    R.string.trace_report_occluded_passable,
                    occludedPassablePercent,
                    maxOccludedPassablePercent,
                    occludedPassableDeltaPercent,
                    maxOccludedPassableDeltaPercent,
                )
            )
            TraceMetricLine(
                stringResource(
                    R.string.trace_report_stability_deltas,
                    navigationPassableDeltaPercent,
                    maxNavigationPassableDeltaPercent,
                    roadDeltaPercent,
                    maxRoadDeltaPercent,
                    blockageConfidenceDeltaPercent,
                    maxBlockageConfidenceDeltaPercent,
                    verticalReachDeltaPercent,
                    maxVerticalReachDeltaPercent,
                    floodReachDeltaPercent,
                    maxFloodReachDeltaPercent,
                    widthRetentionDeltaPercent,
                    maxWidthRetentionDeltaPercent,
                )
            )
            TraceMetricLine(
                stringResource(
                    R.string.trace_report_obstacle_stability,
                    report.avgRawObstacleDetectionCount,
                    report.avgRawObstacleDetectionCountDelta,
                    report.maxRawObstacleDetectionCountDelta,
                    report.avgObstacleCountDelta,
                    report.maxObstacleCountDelta,
                )
            )
            TraceMetricLine(stringResource(R.string.trace_report_dominant_classes, dominantClasses))
            TraceMetricLine(stringResource(R.string.trace_report_raw_obstacle_classes, rawObstacleClasses))
            TraceMetricLine(stringResource(R.string.trace_report_tracked_obstacle_categories, trackedObstacleCategories))
            TraceMetricLine(
                stringResource(
                    R.string.trace_report_road_vehicle_sources,
                    roadVehicleSources,
                    avgRoadVehicleConfidencePercent,
                    roadVehicleReasons,
                    avgRoadVehicleBottomYPercent,
                    avgRoadVehicleCenterBandOverlapPercent,
                    avgRoadVehicleAreaPercent,
                )
            )
            TraceMetricLine(stringResource(R.string.trace_report_blockage_reasons, blockageReasons))
            TraceMetricLine(stringResource(R.string.trace_report_message_keys, messageKeys))

            Spacer(modifier = Modifier.height(4.dp))

            if (warnings.isEmpty()) {
                Text(
                    text = stringResource(R.string.trace_report_budget_ok),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                )
            } else {
                Text(
                    text = stringResource(R.string.trace_report_budget_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                )
                warnings.forEach { warning ->
                    Text(
                        text = "• $warning",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TraceMetricLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

private fun List<String>.displayOrUnknown(unknownValue: String): String =
    ifEmpty { listOf(unknownValue) }.joinToString()

private fun formatTimestamp(timestamp: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    return Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}

private fun handleTraceEffect(
    context: Context,
    effect: TraceReplayUiEffect,
    copiedMessage: String,
    fileMissingMessage: String,
) {
    when (effect) {
        is TraceReplayUiEffect.ShowToast ->
            Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()

        is TraceReplayUiEffect.CopyToClipboard -> {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(effect.label, effect.text))
            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
        }

        is TraceReplayUiEffect.ShareTraceFile ->
            shareTraceFile(context, effect.sessionId, fileMissingMessage)
    }
}

private fun shareTraceFile(
    context: Context,
    sessionId: String,
    fileMissingMessage: String,
) {
    val traceFile = File(File(context.filesDir, "traces"), "trace_$sessionId.jsonl")
    if (!traceFile.exists() || !traceFile.isFile) {
        Toast.makeText(context, fileMissingMessage, Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", traceFile)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.title_share_trace_file)))
}
