package com.sailens.presentation.scene

import android.graphics.Bitmap
import com.sailens.domain.model.perception.ObstacleDetection
import com.sailens.domain.model.scene.SceneEvent
import com.sailens.domain.model.scene.SceneDebugInfo
import com.sailens.presentation.device.SpeechEngineState

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
    /**
     * 有可用的 VLM，"描述场景"这个动作可以提供。
     *
     * 为 false 时入口**不显示**，而不是显示成灰的：对盲人用户来说，一个永远不能用的控件只是
     * 多一次徒劳的焦点停留。模型有没有装好属于诊断信息，归设置页。
     */
    val isSceneDescriptionAvailable: Boolean = false,
    /** 一次场景描述正在生成中（模型加载 + 解码，秒级）。 */
    val isDescribingScene: Boolean = false,
    /**
     * 最近一次成功的场景描述全文，会话停止时清空。
     *
     * 它是历史，不是当前场景——VLM 看的是用户按下按钮那一刻的画面，几秒后画面早就变了。
     * 不要用它驱动任何实时判断。
     */
    val lastSceneDescription: String? = null,
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

internal fun shouldAlertSpeechUnavailable(
    previousEngineState: SpeechEngineState,
    newEngineState: SpeechEngineState,
    speechEnabled: Boolean,
    screenReaderActive: Boolean,
): Boolean =
    previousEngineState != SpeechEngineState.UNAVAILABLE &&
        newEngineState == SpeechEngineState.UNAVAILABLE &&
        speechEnabled &&
        !screenReaderActive

internal fun SceneAnalysisUiState.hasGuidanceOutputChannel(): Boolean =
    hasSpeechOutputChannel() || isHapticsEnabled

/**
 * 语音那一条通道当前可用（自带 TTS 活着，或者读屏在替它播报）。
 */
internal fun SceneAnalysisUiState.hasSpeechOutputChannel(): Boolean =
    isSpeechEnabled && (isScreenReaderActive || speechEngineState != SpeechEngineState.UNAVAILABLE)

/**
 * 现在可以发起一次场景描述。
 *
 * 除了要有模型、且没有正在生成中，还必须有**语音**通道：一句自然语言描述没有触觉编码，
 * 震动词汇是一套封闭的方位/失效符号（见 [com.sailens.presentation.device.GuidanceHaptic]），
 * 没法用来表达"前方三米有一根电线杆"。纯震动模式下提供这个入口等于提供一个按下去没有回应
 * 的按钮，而用户看不到屏幕上的任何补偿性提示。
 */
internal fun SceneAnalysisUiState.canDescribeScene(): Boolean =
    isSceneDescriptionAvailable && !isDescribingScene && hasSpeechOutputChannel()
