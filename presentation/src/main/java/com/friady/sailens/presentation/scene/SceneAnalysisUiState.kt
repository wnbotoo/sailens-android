package com.friady.sailens.presentation.scene

import android.graphics.Bitmap
import com.friady.sailens.domain.model.perception.DetectedInstance
import com.friady.sailens.domain.model.perception.DetectedObstacle
import com.friady.sailens.domain.model.scene.SceneEvent
import com.friady.sailens.domain.model.scene.SceneDebugInfo
import com.friady.sailens.domain.model.trace.TraceReplayReport
import com.friady.sailens.domain.model.trace.TraceSessionDescriptor

data class SceneAnalysisUiState(
    val currentScreen: SceneAnalysisScreen = SceneAnalysisScreen.LIVE_ANALYSIS,
    val isInitializing: Boolean = false,
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val isPaused: Boolean = false,
    val isSpeechEnabled: Boolean = true,
    val isHapticsEnabled: Boolean = true,
    val isSpeechReady: Boolean = false,
    val enabledOverlayModes: Set<SceneOverlayMode> = emptySet(),
    val overlayMode: SceneOverlayMode = SceneOverlayMode.PASSABLE_AREA_MASK,
    val segMask: Bitmap? = null,
    val frameDisplayWidth: Int? = null,
    val frameDisplayHeight: Int? = null,
    val trackedObstacles: List<DetectedObstacle> = emptyList(),
    val instanceDetections: List<DetectedInstance> = emptyList(),
    val latestSceneDebugInfo: SceneDebugInfo? = null,
    val lastEvents: List<SceneEvent> = emptyList(),
    val eventCount: Int = 0,
    val frameCount: Long = 0,
    val isTraceReplayLoading: Boolean = false,
    val traceSessions: List<TraceSessionDescriptor> = emptyList(),
    val selectedTraceSessionId: String? = null,
    val traceReplayReport: TraceReplayReport? = null,
    val traceReplayWarnings: List<String> = emptyList(),
    val errorMessage: String? = null,
)
