package com.sailens.shell.design.components

import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * One option in a single-choice group. The **whole row** is the touch target (≥56dp) and TalkBack
 * reads it as one radio-button node ("<label>, selected/not selected"). The inner [RadioButton] is
 * decorative (`onClick = null`) so it is not a second, tiny focus target. Wrap the group container
 * with `Modifier.selectableGroup()` so screen readers announce position in set.
 */
@Composable
fun SelectableRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    SettingsRowScaffold(
        label = label,
        interactionModifier = Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        ),
        modifier = modifier,
        supportingText = supportingText,
        trailing = {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
            )
        },
    )
}
