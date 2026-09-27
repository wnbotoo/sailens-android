package com.sailens.shell.capture

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sailens.guidance.trace.capture.CaptureModes
import com.sailens.shell.R
import com.sailens.shell.design.components.SailensScaffold
import com.sailens.shell.design.components.ToggleRow
import com.sailens.shell.design.theme.SailensDimens
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.util.Date

@Composable
internal fun FieldCaptureScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FieldCaptureViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shareTitle = stringResource(R.string.title_share_capture)
    val exportFailed = stringResource(R.string.field_capture_export_failed)
    val actionFailed = stringResource(R.string.field_capture_action_failed)
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is FieldCaptureEffect.Share -> shareZip(context, effect.zip, shareTitle)
                is FieldCaptureEffect.ExportFailed ->
                    Toast.makeText(context, exportFailed.format(effect.reason), Toast.LENGTH_LONG).show()
                FieldCaptureEffect.ActionFailed -> Toast.makeText(context, actionFailed, Toast.LENGTH_LONG).show()
            }
        }
    }

    pendingDelete?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.title_field_capture_delete)) },
            text = { Text(stringResource(R.string.field_capture_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(sessionId)
                    },
                ) { Text(stringResource(R.string.btn_field_capture_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.btn_cancel_field_capture_delete))
                }
            },
        )
    }

    SailensScaffold(
        title = stringResource(R.string.title_field_capture_page),
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
            // The mode is read once when a Guidance session starts, so changing it mid-session would
            // not do what the switch shows; it is locked until the recording ends.
            ToggleRow(
                label = stringResource(R.string.field_capture_switch),
                checked = state.enabled,
                onCheckedChange = viewModel::setEnabled,
                supportingText = stringResource(R.string.field_capture_switch_supporting),
                enabled = !state.capturing,
            )
            TimingSyncSection(
                armed = state.timingSyncArmed,
                enabled = !state.capturing,
                onArmedChange = viewModel::setTimingSyncArmed,
            )
            if (state.capturing) {
                Text(
                    text = stringResource(R.string.field_capture_recording_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state.exporting) {
                Text(
                    text = stringResource(R.string.field_capture_exporting_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state.sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.field_capture_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm)) {
                items(state.sessions, key = { it.sessionId }) { session ->
                    CaptureRow(
                        session = session,
                        actionsEnabled = !state.capturing && state.busySessionId == null,
                        onKeep = { viewModel.setKept(session.sessionId, !session.pinned) },
                        onExport = { viewModel.export(session.sessionId) },
                        onDelete = { pendingDelete = session.sessionId },
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureRow(
    session: CaptureSummary,
    actionsEnabled: Boolean,
    onKeep: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val started = DateFormat.getMediumDateFormat(context).format(Date(session.startedWallMs)) + " " +
        DateFormat.getTimeFormat(context).format(Date(session.startedWallMs))
    val status = buildList {
        add(
            when (session.mode) {
                CaptureModes.TIMING_SYNC -> stringResource(R.string.field_capture_mode_timing_sync)
                CaptureModes.FIELD_EVIDENCE -> stringResource(R.string.field_capture_mode_field_evidence)
                else -> session.mode ?: "?"
            },
        )
        add(
            when {
                session.active -> stringResource(R.string.field_capture_status_recording)
                session.complete -> stringResource(R.string.field_capture_status_complete)
                else -> stringResource(R.string.field_capture_status_incomplete, session.failureReason ?: "?")
            },
        )
        if (session.pinned) add(stringResource(R.string.field_capture_status_kept))
        if (session.exported) add(stringResource(R.string.field_capture_status_exported))
    }.joinToString(" · ")

    Surface(tonalElevation = SailensDimens.spaceXs, shape = MaterialTheme.shapes.medium) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SailensDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm),
        ) {
            Text(text = started, style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(
                    R.string.field_capture_row_summary,
                    Formatter.formatShortFileSize(context, session.bytes),
                    status,
                    session.framesStored.toInt(),
                    session.markers.toInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!session.active) {
                // Wraps under large font sizes instead of squeezing three buttons into one line.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm),
                    verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm),
                ) {
                    OutlinedButton(onClick = onKeep, enabled = actionsEnabled) {
                        Text(
                            stringResource(
                                if (session.pinned) R.string.btn_field_capture_unkeep else R.string.btn_field_capture_keep,
                            ),
                        )
                    }
                    OutlinedButton(onClick = onExport, enabled = actionsEnabled) {
                        Text(stringResource(R.string.btn_field_capture_export))
                    }
                    OutlinedButton(onClick = onDelete, enabled = actionsEnabled) {
                        Text(stringResource(R.string.btn_field_capture_delete))
                    }
                }
            }
        }
    }
}

private fun shareZip(context: Context, zip: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, title))
}

/**
 * The one-shot timing-sync burst: armed here, it runs for about 15 s at the next Guidance start,
 * whatever the capture switch says, and then Guidance carries on without capture.
 */
@Composable
private fun TimingSyncSection(armed: Boolean, enabled: Boolean, onArmedChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm)) {
        Text(text = stringResource(R.string.field_capture_timing_sync_title), style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(
                if (armed) R.string.field_capture_timing_sync_armed else R.string.field_capture_timing_sync_supporting,
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        OutlinedButton(onClick = { onArmedChange(!armed) }, enabled = enabled) {
            Text(
                stringResource(
                    if (armed) R.string.btn_field_capture_timing_sync_cancel else R.string.btn_field_capture_timing_sync_arm,
                ),
            )
        }
    }
}
