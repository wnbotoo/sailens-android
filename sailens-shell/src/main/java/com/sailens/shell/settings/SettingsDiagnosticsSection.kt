package com.sailens.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sailens.shell.R
import com.sailens.shell.design.components.SettingRow
import com.sailens.shell.design.components.SettingsSection

@Composable
internal fun SettingsDiagnosticsSection(
    onOpenTraceReports: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSection(
        title = stringResource(R.string.settings_section_diagnostics),
        modifier = modifier,
    ) {
        SettingRow(
            title = stringResource(R.string.btn_open_trace_reports),
            supportingText = stringResource(R.string.settings_diagnostics_trace_reports_supporting),
            onClick = onOpenTraceReports,
        )
    }
}
