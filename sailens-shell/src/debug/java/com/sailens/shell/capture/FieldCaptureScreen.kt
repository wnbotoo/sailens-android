package com.sailens.shell.capture

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is FieldCaptureEffect.Share -> shareZip(context, effect.zip, shareTitle)
                is FieldCaptureEffect.ExportFailed ->
                    Toast.makeText(context, exportFailed.format(effect.reason), Toast.LENGTH_LONG).show()
            }
        }
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
            ToggleRow(
                label = stringResource(R.string.field_capture_switch),
                checked = state.enabled,
                onCheckedChange = viewModel::setEnabled,
                supportingText = stringResource(R.string.field_capture_switch_supporting),
            )
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
                        busy = state.busySessionId == session.sessionId,
                        onKeep = { viewModel.setKept(session.sessionId, !session.pinned) },
                        onExport = { viewModel.export(session.sessionId) },
                        onDelete = { viewModel.delete(session.sessionId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureRow(
    session: CaptureSummary,
    busy: Boolean,
    onKeep: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val started = DateFormat.getMediumDateFormat(context).format(Date(session.startedWallMs)) + " " +
        DateFormat.getTimeFormat(context).format(Date(session.startedWallMs))
    val status = buildList {
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
                Row(horizontalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm)) {
                    OutlinedButton(onClick = onKeep, enabled = !busy) {
                        Text(
                            stringResource(
                                if (session.pinned) R.string.btn_field_capture_unkeep else R.string.btn_field_capture_keep,
                            ),
                        )
                    }
                    OutlinedButton(onClick = onExport, enabled = !busy) {
                        Text(stringResource(R.string.btn_field_capture_export))
                    }
                    OutlinedButton(onClick = onDelete, enabled = !busy) {
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
