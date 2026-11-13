package com.sailens.shell.safetynotice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.sailens.shell.R
import com.sailens.shell.design.theme.SailensDimens

/**
 * The safety notice, shown instead of the start of Guidance until its current version is confirmed;
 * see [SafetyNoticeGate].
 *
 * Read in this order: the title (a heading), the five paragraphs, where to find them again, and last
 * the one button. There is no second button: Back, or a tap outside, is how the notice is left
 * without confirming it. The text scrolls on its own between the title and the button, so at a large
 * font size the button stays on screen.
 */
@Composable
internal fun SafetyNoticeDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.safety_notice_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceMd),
            ) {
                SafetyNoticeParagraphs()
                Text(
                    text = stringResource(R.string.safety_notice_footer),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.safety_notice_confirm))
            }
        },
        // The notice in the normal text colour, not the muted one dialogs use by default.
        textContentColor = MaterialTheme.colorScheme.onSurface,
    )
}
