package com.sailens.shell.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
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
import com.sailens.shell.describe.DescribeScreen
import com.sailens.shell.device.ScreenReaderAnnouncer
import com.sailens.shell.navigation.DescribeKey
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
 *
 * All four pipeline combinations are routed here, and they route to different places (§5.1):
 *
 * | Guidance | Describe | Start destination |
 * |---|---|---|
 * | available | available | guidance, with a Describe action |
 * | available | — | guidance, no Describe control |
 * | — | available | Describe |
 * | — | — | the zero-pipeline screen |
 *
 * A Describe-only edition must not land on the guidance screen: that screen's whole shape is a
 * start/stop control for a navigation session it does not have, and the one thing it *can* do
 * would not be on it.
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
    /**
     * The one route to the screen reader. Its collector lives here, at the root, because this is
     * the only composable that exists for as long as the app does: a per-screen collector drops
     * every announcement raised while another screen is on top (architecture.md §6.6).
     */
    screenReaderAnnouncer: ScreenReaderAnnouncer? = null,
    modifier: Modifier = Modifier,
) {
    val capabilities = remember(spec) { PipelinePreflight.evaluate(spec) }
    val failure = remember(capabilities, spec) {
        capabilities.configurationFailure(spec.expectations)
    }

    val view = LocalView.current
    if (screenReaderAnnouncer != null) {
        LaunchedEffect(screenReaderAnnouncer, view) {
            screenReaderAnnouncer.announcements.collect { text -> view.announceForAccessibility(text) }
        }
    }

    SailensTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (failure != null) {
                val message = configurationFailureMessage(failure)
                check(!failFastOnConfigurationError) {
                    "Sailens configuration error: $message"
                }
                LaunchedEffect(message) {
                    configurationFailureSignal?.signalOnce(message, diagnostic = failure.toString())
                }
                FatalConfigurationScreen(message = message, modifier = modifier)
                return@Surface
            }

            if (capabilities.hasNoAvailablePipeline) {
                ZeroPipelineScreen(modifier = modifier)
                return@Surface
            }

            val guidanceAvailable =
                capabilities.startDestination == SailensStartDestination.GUIDANCE
            val describeAvailable = capabilities.offersDescribeAction
            val startKey: NavKey = when (capabilities.startDestination) {
                SailensStartDestination.GUIDANCE -> LiveKey
                SailensStartDestination.DESCRIBE -> DescribeKey
                // Unreachable: hasNoAvailablePipeline returned above. Named rather than elided so
                // a new destination cannot be added without deciding what happens here.
                SailensStartDestination.NONE -> LiveKey
            }
            val backStack: NavBackStack<NavKey> = rememberNavBackStack(startKey)
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
                            // Null when Describe is not available in this edition: a control that
                            // cannot answer is worse than no control for someone who has to reach
                            // it by touch exploration.
                            onOpenDescribe = if (describeAvailable) {
                                { backStack.add(DescribeKey) }
                            } else {
                                null
                            },
                        )
                    }
                    entry<DescribeKey> {
                        DescribeScreen(
                            onOpenSettings = { backStack.add(SettingsKey) },
                            // Describe-only editions start here, so there is nothing to go back to.
                            onNavigateBack = if (guidanceAvailable) {
                                { backStack.removeLastOrNull() }
                            } else {
                                null
                            },
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
 * a pavement can act on. The developer-facing detail (which tensor, how many classes) is logged
 * separately rather than spoken.
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
                is StaticUnavailableReason.ModelOutputUnreadable -> R.string.config_reason_model_output
                is StaticUnavailableReason.ModelClassCountMismatch -> R.string.config_reason_class_count
                StaticUnavailableReason.EngineUnavailable -> R.string.config_reason_engine
            }
        )

        PipelineAvailability.NotConfigured -> stringResource(R.string.config_reason_not_configured)
        PipelineAvailability.Available -> ""
    }
    return stringResource(R.string.config_failure_message, pipeline, reason)
}
