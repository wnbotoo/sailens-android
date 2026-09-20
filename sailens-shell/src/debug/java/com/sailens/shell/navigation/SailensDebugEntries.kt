package com.sailens.shell.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.sailens.shell.trace.TraceReportScreen
import com.sailens.shell.trace.TraceSessionsScreen

/**
 * Debug-variant trace/diagnostics destinations. This file exists only in `src/debug`, so the trace
 * screens and their ViewModel are physically excluded from the release variant.
 */
fun EntryProviderScope<NavKey>.sailensDebugEntries(backStack: NavBackStack<NavKey>) {
    entry<TraceSessionsKey> {
        TraceSessionsScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            onOpenReport = { sessionId -> backStack.add(TraceReportKey(sessionId)) },
        )
    }
    entry<TraceReportKey> { key ->
        TraceReportScreen(
            sessionId = key.sessionId,
            onNavigateBack = { backStack.removeLastOrNull() },
        )
    }
}
