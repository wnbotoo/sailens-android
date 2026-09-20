package com.sailens.shell.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.sailens.shell.design.theme.SailensDimens

/**
 * Shared scaffold for settings-style rows ([SettingRow] / [ToggleRow] / [SelectableRow]):
 * the whole row is one ≥56dp touch target, with a weighted label column on the left and an
 * optional trailing control on the right.
 *
 * [interactionModifier] carries the row-level interaction (clickable / toggleable / selectable)
 * and is applied BEFORE padding so the ripple and the accessibility touch target cover the full
 * row. Keep trailing controls decorative (null click handlers) so TalkBack sees a single node.
 */
@Composable
internal fun SettingsRowScaffold(
    label: String,
    interactionModifier: Modifier,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = SailensDimens.spaceSm,
    valueText: String? = null,
    supportingText: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SailensDimens.minTouchTarget)
            .then(interactionModifier)
            .padding(vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceXs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (valueText != null) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(SailensDimens.spaceMd))
            trailing()
        }
    }
}
