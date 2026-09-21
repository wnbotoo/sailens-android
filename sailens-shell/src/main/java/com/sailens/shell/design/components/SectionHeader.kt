package com.sailens.shell.design.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.sailens.shell.design.theme.SailensDimens

/**
 * A titled section divider. Marked as a [heading] so TalkBack users can jump between sections.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(top = SailensDimens.spaceSm, bottom = SailensDimens.spaceXs)
            .semantics { heading() },
    )
}
