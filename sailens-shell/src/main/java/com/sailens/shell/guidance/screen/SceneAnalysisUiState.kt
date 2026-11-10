package com.sailens.shell.guidance.screen

import android.graphics.Bitmap
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneDebugInfo
import com.sailens.output.SpeechEngineState
import com.sailens.shell.guidance.overlay.SceneOverlayMode
import com.sailens.shell.guidance.safety.GuidanceSafetyState
import com.sailens.shell.guidance.safety.PromptHoldView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class SceneAnalysisUiState(
    val isInitializing: Boolean = false,
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val isPaused: Boolean = false,
    val isSpeechEnabled: Boolean = true,
    val isHapticsEnabled: Boolean = true,
    val isSpeechReady: Boolean = false,
    val speechEngineState: SpeechEngineState = SpeechEngineState.IDLE,
    /**
     * 系统有屏幕阅读器在工作。为 true 时提示交给它播报，本应用的 TTS 让位，避免念两遍。
     */
    val isScreenReaderActive: Boolean = false,
    /**
     * 辅助被系统打断过（息屏、来电、进后台），且用户尚未确认。
     *
     * 必须让用户可感知：静默停止意味着他会继续举着一个不工作的手机往前走，
     * 而"没有提示"和"前方安全"在他那里是同一种体验。
     */
    val wasInterrupted: Boolean = false,
    val showDiagnostics: Boolean = false,
    val enabledOverlayModes: Set<SceneOverlayMode> = emptySet(),
    val overlayMode: SceneOverlayMode = SceneOverlayMode.PASSABLE_AREA_MASK,
    val segMask: Bitmap? = null,
    val maskSourceAgeMs: Long = 0L,
    val frameDisplayWidth: Int? = null,
    val frameDisplayHeight: Int? = null,
    val obstacleDetections: List<ObstacleDetection> = emptyList(),
    val latestSceneDebugInfo: SceneDebugInfo? = null,
    /**
     * 当帧决策输出，逐帧覆盖，只用于诊断。
     *
     * **不要**用它驱动 UI 或重播：冷却过滤会让绝大多数帧返回空列表，所以这个字段在一次播报后
     * 通常下一帧就空了。实时卡片使用 [activeStatusEvent]，重播使用 [lastAnnouncedEvent]。
     */
    val lastEvents: List<SceneEvent> = emptyList(),
    /**
     * 当前仍有效的状态提示。新事件到来时替换；没有新事件时保留到 [SceneEvent.expiresAt]，
     * 避免冷却造成的空帧让卡片瞬间闪回"无风险"。
     *
     * 这个字段只描述当前场景，不能用于重播；过期后必须清空。
     */
    val activeStatusEvent: SceneEvent? = null,
    /**
     * 最近一条**请求通过可达通道输出**的提示，只在语音/读屏/震动至少一条通道启用时更新，
     * 会话停止时清空。
     *
     * 它是重播历史，不代表风险仍然存在；实时状态请使用 [activeStatusEvent]。
     */
    val lastAnnouncedEvent: SceneEvent? = null,
    val errorMessage: String? = null,
    /**
     * Where Guidance stands for the user, as the safety monitor derives it. The debug panel shows it and it is not
     * announced; the monitor it comes from decides when the alerts about Guidance itself are due
     * ([com.sailens.shell.guidance.safety.GuidanceSystemAlerts]). Nothing reads this field to decide.
     */
    val safetyState: GuidanceSafetyState = GuidanceSafetyState.Idle,
    /**
     * Ordinary prompts are held after a stop: its cause, and whether it still holds or fresh results are awaited. The
     * status card shows it in place of an event; written with [safetyState], on each change of either.
     */
    val promptHold: PromptHoldView? = null,
)

/**
 * The start request, the flow's start and the user's stop are facts the safety monitor and this state both hold. Each
 * reaches the monitor ([tellMonitor]) before it is written here, so that a check comparing the two at the start of an
 * input never finds this state ahead of the monitor.
 */
internal inline fun MutableStateFlow<SceneAnalysisUiState>.startRequested(tellMonitor: () -> Unit) {
    tellMonitor()
    update { it.copy(isLoading = true) }
}

/** The analysis flow is collected; see [startRequested] for the order. */
internal inline fun MutableStateFlow<SceneAnalysisUiState>.flowStarted(tellMonitor: () -> Unit) {
    tellMonitor()
    update { it.copy(isInitializing = false, isRunning = true, isLoading = false) }
}

/** The user stopped Guidance: everything the session showed is cleared; see [startRequested] for the order. */
internal inline fun MutableStateFlow<SceneAnalysisUiState>.stopped(tellMonitor: () -> Unit) {
    tellMonitor()
    update {
        it.copy(
            isRunning = false,
            isInitializing = false,
            isLoading = false,
            // 用户主动停止，中断告警自然作废。
            wasInterrupted = false,
            lastEvents = emptyList(),
            activeStatusEvent = null,
            lastAnnouncedEvent = null,
            segMask = null,
            maskSourceAgeMs = 0L,
            frameDisplayWidth = null,
            frameDisplayHeight = null,
            obstacleDetections = emptyList(),
            latestSceneDebugInfo = null,
        )
    }
}

/**
 * 合并新一帧的决策输出和当前状态卡片。
 *
 * 冷却后的空列表不等于风险消失，只表示本帧无需重复播报，因此旧状态保留到事件过期。
 */
internal fun SceneAnalysisUiState.withFrameEvents(
    events: List<SceneEvent>,
    now: Long,
): SceneAnalysisUiState {
    val activeEvent = events.firstOrNull()
        ?: activeStatusEvent?.takeUnless { it.isExpired(now) }
    return copy(
        lastEvents = events,
        activeStatusEvent = activeEvent,
    )
}

/**
 * A result's decisions on the screen. While ordinary prompts are held after a stop ([holdsPrompts]) the card keeps no
 * event, a vision fault's included: the hold shows the stop, and an event kept now would come back once the hold is
 * released. Only the debug list takes them then. Otherwise as [withFrameEvents].
 */
internal fun SceneAnalysisUiState.withResultEvents(events: List<SceneEvent>, now: Long, holdsPrompts: Boolean): SceneAnalysisUiState =
    if (holdsPrompts) copy(lastEvents = events) else withFrameEvents(events, now)

/**
 * A hold began: the card forgets its event, so the event neither shows during the stop nor comes back after it. The
 * release starts the card afresh, from the frame that released it.
 */
internal fun SceneAnalysisUiState.withHoldBegun(): SceneAnalysisUiState = copy(activeStatusEvent = null)
