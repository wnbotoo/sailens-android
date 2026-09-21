package com.sailens.shell.design.components

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * A label + switch where the **whole row** is the touch target (≥56dp) and TalkBack reads it as a
 * single switch node ("<label>, on/off, double-tap to toggle"). The inner [Switch] is decorative
 * (`onCheckedChange = null`) so it is not a second, tiny focus target.
 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    SettingsRowScaffold(
        label = label,
        interactionModifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        modifier = modifier,
        supportingText = supportingText,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        },
    )
}
