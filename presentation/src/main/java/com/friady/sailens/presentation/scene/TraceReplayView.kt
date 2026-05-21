package com.friady.sailens.presentation.scene

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.friady.sailens.domain.model.trace.TraceReplayReport
import com.friady.sailens.domain.model.trace.TraceSessionDescriptor
import com.friady.sailens.presentation.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TraceReplaySessionsScreen(
    state: SceneAnalysisUiState,
    onBackToLiveClick: () -> Unit,
    onRefreshTraceSessionsClick: () -> Unit,
    onLoadLatestTraceReportClick: () -> Unit,
    onLoadTraceSessionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.title_trace_sessions_page),
                style = MaterialTheme.typography.headlineSmall,
            )

            Text(
                text = stringResource(R.string.msg_trace_sessions_page_description),
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onBackToLiveClick,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.btn_back_to_live))
                }
                OutlinedButton(
                    onClick = onRefreshTraceSessionsClick,
                    enabled = !state.isTraceReplayLoading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.btn_refresh_trace_sessions))
                }
            }

            Button(
                onClick = onLoadLatestTraceReportClick,
                enabled = !state.isTraceReplayLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_load_latest_trace_report))
            }

            if (state.isTraceReplayLoading) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.msg_loading_trace_sessions))
                }
            }

            if (state.traceSessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.msg_no_trace_sessions),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.traceSessions, key = { it.sessionId }) { session ->
                        TraceSessionCard(
                            session = session,
                            isSelected = session.sessionId == state.selectedTraceSessionId,
                            onLoadClick = { onLoadTraceSessionClick(session.sessionId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TraceReplayReportScreen(
    state: SceneAnalysisUiState,
    onBackToSessionsClick: () -> Unit,
    onBackToLiveClick: () -> Unit,
    onLoadLatestTraceReportClick: () -> Unit,
    onCopyTraceReportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.title_trace_report_page),
                style = MaterialTheme.typography.headlineSmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onBackToSessionsClick,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.btn_back_to_sessions))
                }
                OutlinedButton(
                    onClick = onBackToLiveClick,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.btn_back_to_live))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onLoadLatestTraceReportClick,
                    enabled = !state.isTraceReplayLoading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.btn_load_latest_trace_report))
                }
                Button(
                    onClick = onCopyTraceReportClick,
                    enabled = state.traceReplayReport != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.btn_copy_trace_report))
                }
            }

            if (state.isTraceReplayLoading) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.msg_loading_trace_report))
                }
            }

            val report = state.traceReplayReport
            if (report == null) {
                Text(
                    text = stringResource(R.string.msg_no_trace_report),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                TraceReplayReportDetail(
                    report = report,
                    warnings = state.traceReplayWarnings,
                )
            }
        }
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
            OutlinedButton(
                onClick = onLoadClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
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
    val droppedRatePercent = if (report.totalFrames > 0) {
        (report.droppedFrames.toDouble() / report.totalFrames * 100).toInt()
    } else {
        0
    }
    val blockedRatePercent = (report.blockedFrameRate * 100).toInt()
    val dangerRatePercent = (report.dangerousFrameRate * 100).toInt()
    val messageKeys = report.uniqueMessageKeys.ifEmpty {
        listOf(stringResource(R.string.value_unknown))
    }.joinToString()

    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TraceMetricLine(stringResource(R.string.trace_report_session_id, report.sessionId))
            TraceMetricLine(
                stringResource(
                    R.string.trace_report_pipeline_mode,
                    report.pipelineMode ?: stringResource(R.string.value_unknown),
                )
            )
            TraceMetricLine(
                stringResource(
                    R.string.trace_report_target_hardware,
                    report.targetHardwareProfile ?: stringResource(R.string.value_unknown),
                )
            )
            TraceMetricLine(stringResource(R.string.trace_report_frames, report.totalFrames))
            TraceMetricLine(stringResource(R.string.trace_report_dropped, report.droppedFrames, droppedRatePercent))
            TraceMetricLine(stringResource(R.string.trace_report_avg_pipeline, report.avgTotalPipelineMs))
            TraceMetricLine(stringResource(R.string.trace_report_p95_pipeline, report.p95TotalPipelineMs))
            TraceMetricLine(stringResource(R.string.trace_report_avg_inference, report.avgInferenceMs))
            TraceMetricLine(stringResource(R.string.trace_report_errors, report.errorCount))
            TraceMetricLine(stringResource(R.string.trace_report_blocked_rate, blockedRatePercent))
            TraceMetricLine(stringResource(R.string.trace_report_danger_rate, dangerRatePercent))
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

private fun formatTimestamp(timestamp: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    return Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}

