package com.friady.sailens.presentation.scene

// action: viewmodel -> view
sealed interface SceneAnalysisUiEffect {
    data class ShowToast(val message: String) : SceneAnalysisUiEffect
    data class CopyToClipboard(
        val label: String,
        val text: String,
    ) : SceneAnalysisUiEffect
}