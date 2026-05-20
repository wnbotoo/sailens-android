package com.friady.sailens.presentation.scene

import android.graphics.Bitmap
import com.friady.sailens.domain.model.scene.SceneEvent

data class SceneAnalysisUiState(
    val isInitializing: Boolean = false,
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val isPaused: Boolean = false,
    val isSpeechEnabled: Boolean = true,
    val isHapticsEnabled: Boolean = true,
    val isSpeechReady: Boolean = false,
    val segMask: Bitmap? = null,
    val lastEvents: List<SceneEvent> = emptyList(),
    val eventCount: Int = 0,
    val frameCount: Long = 0,
    val errorMessage: String? = null,
)
