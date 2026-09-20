package com.sailens.shell.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import com.sailens.shell.R
import com.sailens.shell.design.theme.SailensDimens

/**
 * Shown when the edition promised a pipeline it cannot provide.
 *
 * A single, quiet, readable state -- not a crash loop and not a dialog the user has to dismiss to
 * reach an app that will not work anyway. It is marked as an assertive live region so a screen
 * reader announces it on arrival rather than waiting to be explored, and the non-visual signal is
 * sent separately by [ConfigurationFailureSignal] for the case where no screen reader is running.
 */
@Composable
fun FatalConfigurationScreen(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SailensDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(SailensDimens.itemSpacing, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.config_failure_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.config_failure_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Shown when nothing is configured or nothing is available, and nothing was promised.
 *
 * This is a legal state (architecture.md §5.1), so it reads as a setup step rather than a
 * failure: A ships with no weights on purpose, and a fresh install lands here until someone adds
 * a model.
 */
@Composable
fun ZeroPipelineScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SailensDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(SailensDimens.itemSpacing, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.zero_pipeline_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            text = stringResource(R.string.zero_pipeline_body),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
