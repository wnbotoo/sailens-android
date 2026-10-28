package com.sailens.shell.guidance.screen

import android.graphics.Bitmap
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneDebugInfo
import com.sailens.output.SpeechEngineState
import com.sailens.shell.guidance.overlay.SceneOverlayMode

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
)

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
 * Tells the user once per outage that the app's own speech is gone, as soon as they rely on it.
 *
 * Not a transition rule: an outage that began while the screen reader spoke (or speech was off)
 * raised no alert, and when the user switches back to the app's own speech the engine state does
 * not change again — it is still unavailable. So [update] runs whenever the engine state, the screen
 * reader or the speech setting changes, and the alert is due when the user relies on own speech, the
 * engine is unavailable, and this outage has not been announced yet.
 */
internal class SpeechUnavailableAlert {
    private var announced = false

    /** @return true when the alert is due now; it then counts as announced for this outage. */
    fun update(engineState: SpeechEngineState, speechEnabled: Boolean, screenReaderActive: Boolean): Boolean {
        if (engineState != SpeechEngineState.UNAVAILABLE) {
            announced = false
            return false
        }
        if (announced || !speechEnabled || screenReaderActive) return false
        announced = true
        return true
    }
}
