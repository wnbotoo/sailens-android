package com.sailens.shell.guidance.settings

import android.content.Context
import com.sailens.guidance.model.common.PerceptionProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 感知挡位选择的持久化。运行时生效路径是 PerceptionProfileManager（下次导航会话激活），
 * 这里只负责跨进程重启记住用户选择；应用启动时由 profileBindingsModule 读取初始值。
 */
class PerceptionSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private val _profile = MutableStateFlow(readProfile())
    val profile: StateFlow<PerceptionProfile> = _profile.asStateFlow()

    /**
     * 唯一的调用入口是 SettingsViewModel.setPerceptionProfile——它同时把选择
     * 写入运行时的 PerceptionProfileManager；单独调用会造成持久化与运行时不一致。
     */
    fun setProfile(profile: PerceptionProfile) {
        preferences.edit().putString(KEY_PERCEPTION_PROFILE, profile.name).apply()
        _profile.value = profile
    }

    private fun readProfile(): PerceptionProfile {
        val stored = preferences.getString(KEY_PERCEPTION_PROFILE, null) ?: return DEFAULT_PROFILE
        return PerceptionProfile.entries.find { it.name == stored } ?: DEFAULT_PROFILE
    }

    private companion object {
        const val PREFERENCES_NAME = "perception_settings"
        const val KEY_PERCEPTION_PROFILE = "perception_profile"

        // 出厂默认标准挡（sem+det）：高精度挡留给用户在设置页自行开启
        val DEFAULT_PROFILE = PerceptionProfile.DEFAULT
    }
}
