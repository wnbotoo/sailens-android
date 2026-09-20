package com.sailens.shell.guidance.settings

import android.content.Context
import com.sailens.output.SpeechManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class GuidanceFeedbackSettings(
    val speechEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    /**
     * 语速倍率。默认高于系统的 1.0——盲人 TalkBack 重度用户普遍把语速调到 1.5–3x，
     * 而这里每慢一点都直接变成提示延迟。
     */
    val speechRate: Float = SpeechManager.DEFAULT_SPEECH_RATE,
)

class GuidanceSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<GuidanceFeedbackSettings> = _settings.asStateFlow()

    fun setSpeechEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SPEECH_ENABLED, enabled).apply()
        _settings.update { it.copy(speechEnabled = enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply()
        _settings.update { it.copy(hapticsEnabled = enabled) }
    }

    fun setSpeechRate(rate: Float) {
        val coerced = rate.coerceIn(SpeechManager.MIN_SPEECH_RATE, SpeechManager.MAX_SPEECH_RATE)
        preferences.edit().putFloat(KEY_SPEECH_RATE, coerced).apply()
        _settings.update { it.copy(speechRate = coerced) }
    }

    private fun readSettings(): GuidanceFeedbackSettings {
        return GuidanceFeedbackSettings(
            speechEnabled = preferences.getBoolean(KEY_SPEECH_ENABLED, true),
            hapticsEnabled = preferences.getBoolean(KEY_HAPTICS_ENABLED, true),
            speechRate = preferences
                .getFloat(KEY_SPEECH_RATE, SpeechManager.DEFAULT_SPEECH_RATE)
                .coerceIn(SpeechManager.MIN_SPEECH_RATE, SpeechManager.MAX_SPEECH_RATE),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "guidance_settings"
        const val KEY_SPEECH_ENABLED = "speech_enabled"
        const val KEY_HAPTICS_ENABLED = "haptics_enabled"
        const val KEY_SPEECH_RATE = "speech_rate"
    }
}
