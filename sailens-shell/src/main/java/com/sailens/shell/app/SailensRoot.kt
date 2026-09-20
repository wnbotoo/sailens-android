package com.sailens.shell.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.sailens.shell.R
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.sailens.shell.about.OssLicensesScreen
import com.sailens.shell.navigation.LiveKey
import com.sailens.shell.navigation.OssLicensesKey
import com.sailens.shell.navigation.SettingsKey
import com.sailens.shell.navigation.TraceSessionsKey
import com.sailens.shell.navigation.sailensDebugEntries
import com.sailens.shell.guidance.screen.LiveAnalysisScreen
import com.sailens.shell.settings.SettingsScreen
import com.sailens.shell.design.theme.SailensTheme

/**
 * Top-level app composable: owns the Nav3 back stack and renders destinations through [NavDisplay].
 * The back stack is the single source of navigation truth (the old enum-in-UiState scheme is gone).
 * Trace/diagnostics destinations are contributed by [sailensDebugEntries], which is a no-op in the
 * release variant — those screens are not even compiled into release.
 */
@Composable
fun SailensRoot(
    windowSizeClass: WindowSizeClass,
    spec: SailensAppSpec = SailensAppSpec(),
    /**
     * Debug builds fail fast on a static configuration error so a packaging or wiring mistake is
     * found immediately; release builds enter an accessible fatal state instead of crash-looping
     * (architecture.md §5.2). The host decides which it is -- the shell does not read another
     * application's BuildConfig (§6.11).
     */
    failFastOnConfigurationError: Boolean = false,
    configurationFailureSignal: ConfigurationFailureSignal? = null,
    modifier: Modifier = Modifier,
) {
    val capabilities = remember(spec) { PipelinePreflight.evaluate(spec) }
    val failure = remember(capabilities, spec) {
        capabilities.configurationFailure(spec.expectations)
    }

    SailensTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (failure != null) {
                val message = configurationFailureMessage(failure)
                check(!failFastOnConfigurationError) {
                    "Sailens configuration error: $message"
                }
                LaunchedEffect(message) { configurationFailureSignal?.signalOnce(message) }
                FatalConfigurationScreen(message = message, modifier = modifier)
                return@Surface
            }

            if (capabilities.hasNoAvailablePipeline) {
                ZeroPipelineScreen(modifier = modifier)
                return@Surface
            }

            val backStack: NavBackStack<NavKey> = rememberNavBackStack(LiveKey)
            NavDisplay(
                backStack = backStack,
                modifier = modifier,
                onBack = { backStack.removeLastOrNull() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<LiveKey> {
                        LiveAnalysisScreen(
                            windowSizeClass = windowSizeClass,
                            onOpenSettings = { backStack.add(SettingsKey) },
                        )
                    }
                    entry<SettingsKey> {
                        SettingsScreen(
                            onNavigateBack = { backStack.removeLastOrNull() },
                            onOpenLicenses = { backStack.add(OssLicensesKey) },
                            onOpenTraceReports = { backStack.add(TraceSessionsKey) },
                        )
                    }
                    entry<OssLicensesKey> {
                        OssLicensesScreen(onNavigateBack = { backStack.removeLastOrNull() })
                    }
                    sailensDebugEntries(backStack)
                },
            )
        }
    }
}

/**
 * Localised description of a configuration failure.
 *
 * Deliberately says which pipeline and why, in the user's language: this text is spoken aloud by
 * [ConfigurationFailureSignal], and "something went wrong" is not something a person standing on
 * a pavement can act on.
 */
@Composable
private fun configurationFailureMessage(
    failure: SailensCapabilities.ConfigurationFailure,
): String {
    val pipeline = stringResource(
        when (failure.pipeline) {
            SailensCapabilities.Pipeline.GUIDANCE -> R.string.pipeline_guidance
            SailensCapabilities.Pipeline.DESCRIBE -> R.string.pipeline_describe
        }
    )
    val reason = when (val availability = failure.availability) {
        is PipelineAvailability.Unavailable -> stringResource(
            when (availability.reason) {
                StaticUnavailableReason.ModelSourceMissing -> R.string.config_reason_model_missing
                StaticUnavailableReason.TaxonomyIncompatible -> R.string.config_reason_taxonomy
                StaticUnavailableReason.EngineUnavailable -> R.string.config_reason_engine
            }
        )

        PipelineAvailability.NotConfigured -> stringResource(R.string.config_reason_not_configured)
        PipelineAvailability.Available -> ""
    }
    return stringResource(R.string.config_failure_message, pipeline, reason)
}
