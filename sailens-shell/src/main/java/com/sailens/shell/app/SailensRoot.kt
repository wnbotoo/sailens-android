package com.sailens.shell.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
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
    modifier: Modifier = Modifier,
) {
    SailensTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
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
