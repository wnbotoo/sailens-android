package com.sailens.shell.describe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sailens.shell.R
import com.sailens.shell.camera.CameraViewWithPermission
import com.sailens.shell.design.components.PrimaryActionButton
import com.sailens.shell.design.theme.SailensDimens
import org.koin.androidx.compose.koinViewModel

/**
 * "What is in front of me?" — one question, one answer.
 *
 * This is the whole screen for a Describe-only edition, and the destination the full edition
 * reaches from the guidance screen. It is deliberately small: a view of what the camera sees, one
 * oversized control, and the answer.
 *
 * Accessibility notes that are requirements rather than polish:
 *
 * - the answer is a polite live region, so a screen-reader user hears it without hunting for it,
 *   **unless** the answer is already being handed to the screen reader directly — announcing
 *   the same sentence twice, overlapping, is worse than not announcing it;
 * - the camera view carries no semantics at all: a preview of a scene the user cannot see is not
 *   information, it is a focus stop that wastes their time;
 * - the control's label says what it will do, and changes to a cancel action while a request is in
 *   flight, so the same focus position always does the useful thing.
 */
@Composable
fun DescribeScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
    viewModel: DescribeViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val failureNotice = stringResource(R.string.describe_failed)

    // Screen-reader announcements are not collected here. They go through the shell's single
    // ScreenReaderAnnouncer, whose collector lives at the root, so a navigation warning raised while
    // this screen is on top still reaches TalkBack.

    // Leaving the screen must stop the decode. An answer that arrives after the user has walked
    // away describes somewhere they are no longer standing.
    DisposableEffect(Unit) {
        onDispose { viewModel.cancel() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(SailensDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceLg),
    ) {
        DescribeTopBar(onOpenSettings = onOpenSettings, onNavigateBack = onNavigateBack)

        CameraViewWithPermission(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                // Not information for the person this app is for, and a needless focus stop.
                .clearAndSetSemantics { },
            contentScale = ContentScale.Fit,
        )

        DescribeAnswer(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        PrimaryActionButton(
            text = stringResource(
                if (state.isDescribing) R.string.btn_describe_cancel else R.string.btn_describe
            ),
            onClick = {
                if (state.isDescribing) viewModel.cancel() else viewModel.describe(failureNotice)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DescribeTopBar(
    onOpenSettings: (() -> Unit)?,
    onNavigateBack: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceXs),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SailensDimens.minTouchTarget),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onNavigateBack != null) {
                TextButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.heightIn(min = SailensDimens.minTouchTargetCompact),
                ) {
                    Text(
                        text = stringResource(R.string.btn_back),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Text(
                text = stringResource(R.string.pipeline_describe),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            if (onOpenSettings != null) {
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.heightIn(min = SailensDimens.minTouchTargetCompact),
                ) {
                    Text(
                        text = stringResource(R.string.btn_settings),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun DescribeAnswer(
    state: DescribeUiState,
    modifier: Modifier = Modifier,
) {
    val text = when {
        state.errorMessage != null -> state.errorMessage
        state.description.isNotEmpty() -> state.description
        state.isDescribing -> stringResource(R.string.describe_in_progress)
        else -> stringResource(R.string.describe_idle)
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SailensDimens.itemSpacing),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics {
                // While a screen reader runs, the answer is announced to it directly (through the
                // shell's ScreenReaderAnnouncer); a live region too would read it twice, overlapping.
                if (!state.isScreenReaderActive) liveRegion = LiveRegionMode.Polite
            },
        )
    }
}
