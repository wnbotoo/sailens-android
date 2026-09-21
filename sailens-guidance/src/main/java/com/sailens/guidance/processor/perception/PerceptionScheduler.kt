package com.sailens.guidance.processor.perception

import com.sailens.guidance.config.PerceptionConfig

/**
 * 感知调度器：决定当前帧应运行哪些模型。
 *
 * 取代旧的 frameIndex 奇偶交替——模型组合由挡位决定，频率默认设备尽力而为
 * （管线逐帧串行 + 相机丢帧自限流）：
 *   - sem 每个处理帧都可运行（导航底线，最后降级）
 *   - det 每个处理帧都可运行
 *   - 各 *Fps 字段 > 0 时按时间间隔限频，是动态降级 / 按设备调参的旋钮
 *
 * markXxxRun 在模型*完成*后调用（而非开始时），使间隔反映真实吞吐；
 * 挡位开关（[PerceptionConfig.detectionEnabled]）也在此统一收口。
 *
 * 配置通过 provider 读取：挡位可在会话之间切换（PerceptionProfileManager.activateSelected），
 * 调度器每次判定都取当前生效的配置。
 */
class PerceptionScheduler(
    private val configProvider: () -> PerceptionConfig,
) {
    private var lastSemanticRunMs = 0L
    private var lastDetectionRunMs = 0L

    fun shouldRunSemantic(nowMs: Long): Boolean {
        val fps = configProvider().semanticTargetFps
        if (fps <= 0) return true
        return nowMs - lastSemanticRunMs >= 1000L / fps
    }

    fun shouldRunDetection(nowMs: Long): Boolean {
        val config = configProvider()
        if (!config.detectionEnabled) return false
        val fps = config.detectionTargetFps
        if (fps <= 0) return true
        return nowMs - lastDetectionRunMs >= 1000L / fps
    }

    fun markSemanticRun(nowMs: Long) {
        lastSemanticRunMs = nowMs
    }

    fun markDetectionRun(nowMs: Long) {
        lastDetectionRunMs = nowMs
    }

    fun reset() {
        lastSemanticRunMs = 0L
        lastDetectionRunMs = 0L
    }
}
