package com.sailens.shell.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Type-safe destinations for the Nav3 back stack. Every key is a [NavKey] and `@Serializable` so
 * `rememberNavBackStack` can persist the stack across config changes and process death.
 *
 * The trace/diagnostics keys live here (main) so the gated entry points can push them, but their
 * actual screens are registered only in the debug variant — see `sailensDebugEntries`.
 */
@Serializable
data object LiveKey : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data object OssLicensesKey : NavKey

@Serializable
data object TraceSessionsKey : NavKey

@Serializable
data class TraceReportKey(val sessionId: String? = null) : NavKey
