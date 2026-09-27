package com.sailens.shell.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

internal data class FieldCaptureUiState(
    val enabled: Boolean = false,
    val capturing: Boolean = false,
    /** A ZIP export is running; Guidance is held off until it ends. */
    val exporting: Boolean = false,
    /** The one-shot timing-sync burst will run at the next Guidance start. */
    val timingSyncArmed: Boolean = false,
    val sessions: List<CaptureSummary> = emptyList(),
    val busySessionId: String? = null,
)

internal sealed interface FieldCaptureEffect {
    data class Share(val zip: File) : FieldCaptureEffect
    data class ExportFailed(val reason: String) : FieldCaptureEffect

    /** Keep or delete was refused (a capture started meanwhile) or failed. */
    data object ActionFailed : FieldCaptureEffect
}

/**
 * The capture list (debug builds). While a capture is recording the list is read-only: the switch
 * and the per-session actions are disabled, and the controller refuses them anyway.
 */
internal class FieldCaptureViewModel(
    private val settings: FieldCaptureSettingsStore,
    private val controller: FieldCaptureController,
) : ViewModel() {

    private val _state = MutableStateFlow(FieldCaptureUiState(enabled = settings.enabled.value))
    val state: StateFlow<FieldCaptureUiState> = _state.asStateFlow()

    private val effects = Channel<FieldCaptureEffect>(Channel.BUFFERED)
    val effect: Flow<FieldCaptureEffect> = effects.receiveAsFlow()

    init {
        viewModelScope.launch { settings.enabled.collect { on -> _state.update { it.copy(enabled = on) } } }
        viewModelScope.launch { settings.timingSyncArmed.collect { on -> _state.update { it.copy(timingSyncArmed = on) } } }
        viewModelScope.launch {
            controller.isCapturing.collect { on ->
                _state.update { it.copy(capturing = on) }
                refresh()
            }
        }
        viewModelScope.launch { controller.isManagingFiles.collect { on -> _state.update { it.copy(exporting = on) } } }
        // Opening the list is a retention opportunity and clears stale export ZIPs.
        viewModelScope.launch {
            controller.runMaintenance().join()
            refresh()
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (!_state.value.capturing) settings.setEnabled(enabled)
    }

    fun setTimingSyncArmed(armed: Boolean) {
        if (!_state.value.capturing) settings.setTimingSyncArmed(armed)
    }

    fun setKept(sessionId: String, kept: Boolean) = act(sessionId) {
        if (!controller.setPinned(sessionId, kept)) effects.send(FieldCaptureEffect.ActionFailed)
    }

    fun delete(sessionId: String) = act(sessionId) {
        if (!controller.delete(sessionId)) effects.send(FieldCaptureEffect.ActionFailed)
    }

    fun export(sessionId: String) = act(sessionId) {
        runCatching { controller.export(sessionId) }
            .onSuccess { effects.send(FieldCaptureEffect.Share(it)) }
            .onFailure { effects.send(FieldCaptureEffect.ExportFailed(it.message ?: it.javaClass.simpleName)) }
    }

    private fun act(sessionId: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busySessionId = sessionId) }
            try {
                block()
            } finally {
                _state.update { it.copy(busySessionId = null) }
                refresh()
            }
        }
    }

    private suspend fun refresh() {
        val sessions = runCatching { controller.listSessions() }.getOrDefault(emptyList())
        _state.update { it.copy(sessions = sessions) }
    }
}
