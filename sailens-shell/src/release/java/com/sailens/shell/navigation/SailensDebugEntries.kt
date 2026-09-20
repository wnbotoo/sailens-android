package com.sailens.shell.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * Release variant: no debug destinations. The trace/diagnostics screens live only in `src/debug`,
 * so they are not part of the release binary.
 */
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun EntryProviderScope<NavKey>.sailensDebugEntries(backStack: NavBackStack<NavKey>) {
    // Intentionally empty.
}
