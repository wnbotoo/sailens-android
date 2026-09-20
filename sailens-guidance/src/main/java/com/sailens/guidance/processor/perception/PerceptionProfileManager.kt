package com.sailens.guidance.processor.perception

import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.model.common.PerceptionProfile

/**
 * 感知挡位管理器：设置页选择的挡位（selected）与感知管线正在使用的配置（active）之间的桥。
 *
 * 挡位切换只影响调度参数与模型开关，不能在会话中途生效——obstacle provider 是在会话
 * 开始时按 detectionEnabled 初始化的，DEFAULT→BASIC 中途切换本身安全，但把切换统一收口到
 * 会话边界可避免会话内配置漂移。因此 [selectProfile] 只记录选择，[activateSelected] 由
 * StartSceneAnalysisUseCase 在会话开始（provider 初始化之前）调用，把选择落成本次
 * 会话内不变的 [activeConfig]。
 *
 * 切挡只改 profile 字段（template.copy）：其余字段（provider 类型、阈值、tracker 参数、
 * 未来降级设置的 fps 上限）原样保留自启动装配的初始配置。
 */
class PerceptionProfileManager(
    initialConfig: PerceptionConfig,
) {
    private val template = initialConfig

    // selectProfile 在主线程（设置页），activateSelected/activeConfig 在管线的
    // Default dispatcher —— 两个字段都用 @Volatile 保证跨线程可见
    @Volatile
    var selectedProfile: PerceptionProfile = initialConfig.profile
        private set

    @Volatile
    var activeConfig: PerceptionConfig = initialConfig
        private set

    /**
     * 记录挡位选择，不立即生效。唯一的调用入口是设置页的
     * SettingsViewModel.setPerceptionProfile——它同时负责经 PerceptionSettingsStore
     * 持久化；绕过它单独调用会造成运行时选择与持久化不一致。
     */
    fun selectProfile(profile: PerceptionProfile) {
        selectedProfile = profile
    }

    /**
     * 把当前选中的挡位落成运行配置，返回本次会话使用的 config。
     * 必须在会话开始、obstacle provider 初始化之前调用。
     */
    fun activateSelected(): PerceptionConfig {
        val profile = selectedProfile
        if (profile != activeConfig.profile) {
            activeConfig = template.copy(profile = profile)
        }
        return activeConfig
    }
}
