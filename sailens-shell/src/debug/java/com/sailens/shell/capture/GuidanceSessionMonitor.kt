package com.sailens.shell.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a Guidance session is running, as seen by the debug trace decorator
 * ([CapturingTraceService]): a session runs from its trace `startSession` to `finishSession`.
 * Debug tools that must not run alongside Guidance (the model consistency check) read it.
 */
internal class GuidanceSessionMonitor {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun onStarted() {
        _active.value = true
    }

    fun onFinished() {
        _active.value = false
    }
}
