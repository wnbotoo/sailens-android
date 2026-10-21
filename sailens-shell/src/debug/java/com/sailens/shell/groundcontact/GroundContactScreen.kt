package com.sailens.shell.groundcontact

import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.Placement
import com.sailens.shell.R
import com.sailens.shell.design.components.SailensScaffold
import com.sailens.shell.design.components.SelectableRow
import com.sailens.shell.design.components.SettingsGroupDivider
import com.sailens.shell.design.components.SettingsInlineNote
import com.sailens.shell.design.components.SettingsSection
import com.sailens.shell.design.components.ToggleRow
import com.sailens.shell.design.theme.SailensDimens
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

@Composable
internal fun GroundContactScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroundContactViewModel = koinViewModel(),
) {
    val choice by viewModel.choice.collectAsStateWithLifecycle()
    val settings = choice.settings

    SailensScaffold(
        title = stringResource(R.string.title_ground_contact_page),
        onNavigateBack = onNavigateBack,
        navigateBackContentDescription = stringResource(R.string.cd_navigate_back),
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Edge-to-edge: without this the number keyboard covers the height block's buttons
                // and its error.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(SailensDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceLg),
        ) {
            Text(stringResource(R.string.ground_contact_intro), style = MaterialTheme.typography.bodyMedium)

            SettingsSection(title = stringResource(R.string.ground_contact_section_switch)) {
                ToggleRow(
                    label = stringResource(R.string.ground_contact_enable),
                    checked = choice.groundContactEnabled,
                    onCheckedChange = viewModel::setEnabled,
                    supportingText = stringResource(
                        if (choice.placement == null) {
                            R.string.ground_contact_enable_needs_placement
                        } else {
                            R.string.ground_contact_enable_next_session
                        },
                    ),
                )
            }

            SettingsSection(title = stringResource(R.string.ground_contact_section_placement)) {
                Column(Modifier.selectableGroup()) {
                    SelectableRow(
                        label = stringResource(R.string.ground_contact_placement_none),
                        supportingText = stringResource(R.string.ground_contact_placement_none_supporting, meters(PhoneGeometrySettings.DEFAULT.heightMeters)),
                        selected = choice.placement == null,
                        onClick = { viewModel.setPlacement(null) },
                    )
                    Placement.entries.forEach { placement ->
                        SettingsGroupDivider()
                        SelectableRow(
                            label = stringResource(placementLabel(placement)),
                            supportingText = stringResource(R.string.ground_contact_placement_height, meters(placement.presetHeightMeters)),
                            selected = choice.placement == placement,
                            onClick = { viewModel.setPlacement(placement) },
                        )
                    }
                }
            }

            BodyHeight(initial = choice.bodyHeightCm, onSave = viewModel::saveBodyHeight)

            Text(
                text = stringResource(R.string.ground_contact_result, meters(settings.heightMeters), stringResource(sourceLabel(settings.source))),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            SettingsInlineNote(text = stringResource(R.string.ground_contact_proposals_note))
        }
    }
}

@Composable
private fun BodyHeight(initial: Int?, onSave: (String) -> Boolean) {
    var text by rememberSaveable { mutableStateOf(initial?.toString().orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    val range = PhoneGeometrySettings.BODY_HEIGHT_CM_RANGE
    // The field, its error and its buttons are kept in view together while the keyboard is up.
    val inView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    fun reveal() {
        scope.launch { inView.bringIntoView() }
    }
    fun save() {
        invalid = !onSave(text)
        reveal()
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm),
        modifier = Modifier.bringIntoViewRequester(inView),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter(Char::isDigit).take(3); invalid = false },
            label = { Text(stringResource(R.string.ground_contact_body_height_label)) },
            singleLine = true,
            isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) reveal() },
        )
        if (invalid) {
            // The error lives here rather than in the field's supporting text: a live region is
            // announced when it appears, and the field's own error text is not.
            Text(
                text = stringResource(R.string.ground_contact_body_height_invalid, range.first, range.last),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm)) {
            OutlinedButton(onClick = { save() }) { Text(stringResource(R.string.btn_ground_contact_body_height_save)) }
            OutlinedButton(onClick = { text = ""; save() }) { Text(stringResource(R.string.btn_ground_contact_body_height_clear)) }
        }
    }
}

private fun meters(value: Float) = String.format(Locale.ROOT, "%.2f", value)

private fun placementLabel(placement: Placement) = when (placement) {
    Placement.CHEST_LANYARD -> R.string.ground_contact_placement_chest_lanyard
    Placement.HANDHELD_CHEST -> R.string.ground_contact_placement_handheld_chest
    Placement.WAIST -> R.string.ground_contact_placement_waist
}

private fun sourceLabel(source: HeightSource) = when (source) {
    HeightSource.DEFAULT_SEED -> R.string.ground_contact_source_default_seed
    HeightSource.PRESET -> R.string.ground_contact_source_preset
    HeightSource.CALIBRATED -> R.string.ground_contact_source_calibrated
}
