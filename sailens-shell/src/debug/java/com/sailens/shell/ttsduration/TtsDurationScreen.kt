package com.sailens.shell.ttsduration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sailens.shell.R
import com.sailens.shell.design.components.SailensScaffold
import com.sailens.shell.design.theme.SailensDimens
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

/** The debug TTS-duration tool: how long each Guidance sentence takes to say. */
@Composable
internal fun TtsDurationScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TtsDurationViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SailensScaffold(
        title = stringResource(R.string.title_tts_duration_page),
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
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceLg),
        ) {
            Text(stringResource(R.string.tts_duration_intro), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(SailensDimens.spaceMd)) {
                OutlinedButton(onClick = { viewModel.measure(Locale.SIMPLIFIED_CHINESE) }, enabled = !state.running) {
                    Text(stringResource(R.string.tts_duration_measure_zh))
                }
                OutlinedButton(onClick = { viewModel.measure(Locale.US) }, enabled = !state.running) {
                    Text(stringResource(R.string.tts_duration_measure_en))
                }
            }
            if (state.running) {
                OutlinedButton(onClick = viewModel::cancel) { Text(stringResource(R.string.tts_duration_cancel)) }
            }
            val status = when {
                state.running -> stringResource(R.string.tts_duration_progress, state.done, state.total)
                state.problem != null -> stringResource(R.string.tts_duration_problem, state.problem!!)
                state.savedTo != null -> stringResource(R.string.tts_duration_saved, state.savedTo!!)
                else -> null
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.table?.let { table ->
                SelectionContainer { Text(table, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
