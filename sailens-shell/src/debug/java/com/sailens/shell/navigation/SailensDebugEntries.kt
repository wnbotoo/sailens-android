package com.sailens.shell.navigation

import com.sailens.shell.groundcontact.GroundContactScreen
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.sailens.shell.capture.FieldCaptureScreen
import com.sailens.shell.consistency.ModelConsistencyScreen
import com.sailens.shell.trace.TraceReportScreen
import com.sailens.shell.trace.TraceSessionsScreen

/**
 * Debug-variant trace/diagnostics destinations. This file exists only in `src/debug`, so the trace
 * and field-capture screens and their ViewModels are physically excluded from the release variant.
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
    entry<FieldCaptureKey> {
        FieldCaptureScreen(onNavigateBack = { backStack.removeLastOrNull() })
    }
    entry<ModelConsistencyKey> {
        ModelConsistencyScreen(onNavigateBack = { backStack.removeLastOrNull() })
    }
    entry<GroundContactKey> {
        GroundContactScreen(onNavigateBack = { backStack.removeLastOrNull() })
    }
}
