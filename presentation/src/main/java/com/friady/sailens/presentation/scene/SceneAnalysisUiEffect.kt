package com.friady.sailens.presentation.scene

sealed interface SceneAnalysisUiEffect {
    data class ShowToast(val message: String) : SceneAnalysisUiEffect
    data class CopyToClipboard(
        val label: String,
        val text: String,
    ) : SceneAnalysisUiEffect

    data class ShareTraceFile(
        val sessionId: String,
    ) : SceneAnalysisUiEffect
}
