package com.friady.sailens.ux.dialog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun PermissionRationaleDialog(rationaleState: RationaleState) {
    AlertDialog(
        onDismissRequest = { rationaleState.onRationaleReply(false) },
        title = { Text(rationaleState.title) },
        text = { Text(rationaleState.rationale) },
        confirmButton = {
            Button(onClick = {
                rationaleState.onRationaleReply(true)
            }) {
                Text("confirm")
            }
        },
        dismissButton = {
            Button(onClick = {
                rationaleState.onRationaleReply(false)
            }) {
                Text("cancel")
            }
        }
    )
}

data class RationaleState(
    val title: String,
    val rationale: String,
    val onRationaleReply: (proceed: Boolean) -> Unit
)