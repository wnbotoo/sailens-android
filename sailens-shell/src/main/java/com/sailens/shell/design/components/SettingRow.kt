package com.sailens.shell.design.components

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import com.sailens.shell.design.theme.SailensDimens

/**
 * One row in a settings / info list. Read-only when [onClick] is null (e.g. a model-status line),
 * or a navigation affordance with a trailing chevron when clickable. Either way the row is a single
 * merged semantics node and ≥56dp tall.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    valueText: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    SettingsRowScaffold(
        label = title,
        interactionModifier = if (onClick != null) {
            Modifier.clickable(role = Role.Button, onClick = onClick)
        } else {
            Modifier.semantics(mergeDescendants = true) {}
        },
        modifier = modifier,
        verticalPadding = SailensDimens.spaceMd,
        valueText = valueText,
        supportingText = supportingText,
        trailing = when {
            trailing != null -> trailing
            onClick != null -> {
                {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> null
        },
    )
}
