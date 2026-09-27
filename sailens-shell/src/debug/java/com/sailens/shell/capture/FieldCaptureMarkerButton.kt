package com.sailens.shell.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sailens.guidance.trace.capture.MarkerSource
import com.sailens.shell.R
import com.sailens.shell.design.components.PrimaryActionButton
import com.sailens.shell.design.theme.SailensDimens
import org.koin.compose.koinInject

/**
 * A large "missed alert" button on the Guidance screen, shown only while a field capture is
 * recording (debug builds). Volume Down does the same without looking at the screen.
 */
@Composable
internal fun FieldCaptureMarkerButton(modifier: Modifier = Modifier) {
    val controller: FieldCaptureController = koinInject()
    val markers: MissedAlertMarkers = koinInject()
    val capturing by controller.isCapturing.collectAsStateWithLifecycle()
    if (!capturing) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceXs)) {
        PrimaryActionButton(
            text = stringResource(R.string.btn_mark_missed_alert),
            onClick = { markers.mark(MarkerSource.SCREEN_BUTTON) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.field_capture_marker_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
