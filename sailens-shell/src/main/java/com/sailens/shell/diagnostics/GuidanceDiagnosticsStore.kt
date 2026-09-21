package com.sailens.shell.diagnostics

import com.sailens.shell.config.UiFeatureFlags
import com.sailens.shell.guidance.overlay.SceneOverlayConfig
import com.sailens.shell.guidance.overlay.SceneOverlayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class GuidanceDiagnosticsState(
    val showDiagnostics: Boolean,
    val enabledOverlayModes: Set<SceneOverlayMode>,
    val overlayMode: SceneOverlayMode,
)

class GuidanceDiagnosticsStore(
    private val sceneOverlayConfig: SceneOverlayConfig,
    uiFeatureFlags: UiFeatureFlags,
) {
    private val _state = MutableStateFlow(
        GuidanceDiagnosticsState(
            showDiagnostics = uiFeatureFlags.showDiagnostics,
            enabledOverlayModes = sceneOverlayConfig.enabledOverlayModes,
            overlayMode = sceneOverlayConfig.effectiveInitialMode,
        )
    )
    val state: StateFlow<GuidanceDiagnosticsState> = _state.asStateFlow()

    fun setOverlayMode(overlayMode: SceneOverlayMode) {
        _state.update { it.copy(overlayMode = sceneOverlayConfig.coerceEnabledMode(overlayMode)) }
    }
}
