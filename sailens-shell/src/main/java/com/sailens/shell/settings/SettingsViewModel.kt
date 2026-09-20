package com.sailens.shell.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.guidance.processor.perception.PerceptionProfileManager
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.shell.device.HapticManager
import com.sailens.shell.device.SpeechManager
import com.sailens.shell.diagnostics.GuidanceDiagnosticsState
import com.sailens.shell.diagnostics.GuidanceDiagnosticsStore
import com.sailens.shell.guidance.settings.GuidanceFeedbackSettings
import com.sailens.shell.guidance.settings.GuidanceSettingsStore
import com.sailens.shell.guidance.settings.PerceptionSettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class SettingsUiState(
    val appInfo: AppInfo,
    val feedbackSettings: GuidanceFeedbackSettings,
    val perceptionProfile: PerceptionProfile,
    val diagnostics: GuidanceDiagnosticsState,
)

class SettingsViewModel(
    private val appInfo: AppInfo,
    private val guidanceSettingsStore: GuidanceSettingsStore,
    private val perceptionSettingsStore: PerceptionSettingsStore,
    private val perceptionProfileManager: PerceptionProfileManager,
    private val guidanceDiagnosticsStore: GuidanceDiagnosticsStore,
    private val speechManager: SpeechManager,
    private val hapticManager: HapticManager,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        guidanceSettingsStore.settings,
        perceptionSettingsStore.profile,
        guidanceDiagnosticsStore.state,
    ) { settings, perceptionProfile, diagnostics ->
        SettingsUiState(appInfo, settings, perceptionProfile, diagnostics)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(
                appInfo = appInfo,
                feedbackSettings = guidanceSettingsStore.settings.value,
                perceptionProfile = perceptionSettingsStore.profile.value,
                diagnostics = guidanceDiagnosticsStore.state.value,
            ),
        )

    fun setSpeechEnabled(enabled: Boolean) {
        guidanceSettingsStore.setSpeechEnabled(enabled)
    }

    fun setHapticsEnabled(enabled: Boolean) {
        guidanceSettingsStore.setHapticsEnabled(enabled)
    }

    fun setSpeechRate(rate: Float) {
        guidanceSettingsStore.setSpeechRate(rate)
    }

    /**
     * 用当前语速念一句示例，让用户能听着调而不是盲调。
     *
     * 语速这个设置只能靠听来判断合不合适——看数字对目标用户毫无意义。
     */
    fun previewSpeechRate(sampleText: String) {
        speechManager.setSpeechRate(guidanceSettingsStore.settings.value.speechRate)
        speechManager.speakSystemNotice(sampleText)
    }

    /**
     * 播放一个震动符号让用户体会。
     *
     * 震动词汇表只有先学会才有用；关掉语音后它就是唯一的信息通道，没有一个能反复试的地方，
     * 用户永远学不会"2 短 = 前方"。
     */
    fun previewHaptic(haptic: GuidanceHaptic) {
        hapticManager.play(haptic)
    }

    fun setPerceptionProfile(profile: PerceptionProfile) {
        // store 负责持久化，manager 负责让下一次导航会话用上新挡位
        perceptionSettingsStore.setProfile(profile)
        perceptionProfileManager.selectProfile(profile)
    }
}
