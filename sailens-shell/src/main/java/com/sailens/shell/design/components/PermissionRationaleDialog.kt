package com.sailens.shell.design.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Rationale dialog shown before re-requesting a permission. Button labels are passed in so the
 * hosting module owns localization (this module has no string resources of its own).
 */
@Composable
fun PermissionRationaleDialog(
    rationaleState: RationaleState,
    confirmLabel: String,
    dismissLabel: String,
) {
    AlertDialog(
        onDismissRequest = { rationaleState.onRationaleReply(false) },
        title = { Text(rationaleState.title) },
        text = { Text(rationaleState.rationale) },
        confirmButton = {
            Button(onClick = { rationaleState.onRationaleReply(true) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = { rationaleState.onRationaleReply(false) }) {
                Text(dismissLabel)
            }
        },
    )
}

data class RationaleState(
    val title: String,
    val rationale: String,
    val onRationaleReply: (proceed: Boolean) -> Unit,
)
