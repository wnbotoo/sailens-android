package com.sailens.shell.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.sailens.shell.design.theme.SailensDimens

/**
 * A colored status surface. Optionally announces itself to screen readers as a live region — used
 * for the primary guidance status so TalkBack speaks state changes (e.g. "Obstacle ahead") without
 * the user having to hunt for the text.
 *
 * Pass [spokenDescription] to give the whole card one clean spoken string instead of letting
 * TalkBack stitch the individual lines together.
 */
@Composable
fun StatusCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    liveRegionMode: LiveRegionMode? = null,
    spokenDescription: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            if (liveRegionMode != null) liveRegion = liveRegionMode
            if (spokenDescription != null) contentDescription = spokenDescription
        },
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SailensDimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm),
            content = content,
        )
    }
}
