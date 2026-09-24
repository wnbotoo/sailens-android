package com.sailens.guidance.config

import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.guidance.model.common.SemanticProviderType
import com.sailens.guidance.model.common.ZoneMode

/**
 * 感知配置
 *
 * 两模型职责（按能力划分，不绑定具体模型家族；物理模型由 data 层 ModelCatalog 解析）：
 *   - sem (semanticProviderType = SEGMENTATION_MODEL)
 *       理解可行走区域：哪里能走、地面类型、道路边界
 *       → 驱动 PerceptionRepository.segment() → SegmentationOutput
 *
 *   - det (realtimeObstacleProviderType = DETECTION_MODEL)
 *       实时识别障碍物：有什么、在哪里（BBox）
 *       → 驱动 ObstacleProvider.detect() → ObstacleModelOutput
 *
 * 障碍物形状/遮挡不再依赖独立的实例分割模型：ObstacleOcclusionAnalyzer 用
 * det 跟踪框的接地带与 sem 可行走 mask 交叉验证（见 occlusion* 字段）。
 *
 * 调度模型（profile 驱动，见 [PerceptionProfile] 与 PerceptionScheduler）：
 *   - sem 是导航底线，每个处理帧都可运行，跳过帧复用最近 mask
 *   - det 周期运行，间隔帧由 ObstacleTracker 预测补偿
 *
 * 频率哲学：模型是否运行由挡位决定，跑多快由设备尽力而为——管线逐帧串行、相机侧
 * KEEP_ONLY_LATEST 自适应丢帧，天然自限流。各 *Fps 字段默认 0（不限频），正值上限
 * 是留给动态降级 / 按设备调参的旋钮，不作为产品默认限定。
 */
data class PerceptionConfig(
    val runtimeProfileName: String = "standard",
    val targetHardwareProfile: String = "unknown_hardware",
    val profile: PerceptionProfile = PerceptionProfile.BASIC,
    val semanticProviderType: SemanticProviderType = SemanticProviderType.SEGMENTATION_MODEL,
    val realtimeObstacleProviderType: ObstacleProviderType = ObstacleProviderType.NONE,

    /** sem 帧率上限；<= 0 表示不限频（每个处理帧都运行）。仅供降级/调参用 */
    val semanticTargetFps: Int = 0,

    /** det 帧率上限；<= 0 表示不限频。是否运行 det 由挡位决定，见 [detectionEnabled] */
    val detectionTargetFps: Int = 0,

    /** det 结果 TTL：det 未运行期间，跟踪轨迹超过该时长未被新检测确认即过期 */
    val detectionResultTtlMs: Long = 500,

    /** 语义障碍（BASIC 挡位）的最小面积，占相机画面。竖屏等效：原 0.005 x letterbox mask 面积。 */
    val minObstacleAreaRatio: Float = 0.0089f,
    val maxObstacles: Int = 10,
    val minObstacleConfidence: Float = 0.4f,
    val navigationCorridorCenterWidth: Float = 0.50f,
    val navigationCorridorFarWidth: Float = 0.22f,
    val navigationCorridorHorizonY: Float = 0.35f,
    val semanticObstacleMinBottomY: Float = 0.45f,
    val semanticObstacleMinCorridorOverlapRatio: Float = 0.12f,
    val staticObstacleMinBottomY: Float = 0.55f,
    val staticObstacleMinCorridorOverlapRatio: Float = 0.20f,
    /** 竖屏等效：原 0.35 x letterbox mask 面积（mask 现在只覆盖相机画面，见 AnalysisConfig）。 */
    val maxBackgroundObstacleAreaRatio: Float = 0.622f,

    /** occlusion 抠除：仅对 bbox 底边到达该归一化 Y 以下（足够近）的跟踪障碍生效 */
    val occlusionMinBottomY: Float = 0.55f,

    /** occlusion 抠除：接地带高度占 bbox 高度的比例（从底边向上取），保守小于整框以免过度清除 */
    val occlusionBandHeightRatio: Float = 0.35f,

    val zoneMode: ZoneMode = ZoneMode.THREE,

    val trackerIoUThreshold: Float = 0.3f,
    val trackerMaxMissedFrames: Int = 5,
    val trackerMinStableFrames: Int = 3,
) {
    /** det 是否参与运行（BASIC 挡位或未配置 provider 时关闭） */
    val detectionEnabled: Boolean
        get() = profile != PerceptionProfile.BASIC &&
            realtimeObstacleProviderType != ObstacleProviderType.NONE

    companion object {
        /**
         * 按挡位装配感知配置（模型 provider 类型由调用方按硬件装配决定）。
         *
         * 挡位只决定模型组合，不限定频率——所有模型默认不限频，实际帧率由设备
         * 吞吐决定。需要限频时（动态降级、低端设备调参）再对返回值 copy 覆盖
         * 各 *Fps 字段。
         */
        fun forProfile(
            profile: PerceptionProfile,
            runtimeProfileName: String = "standard",
            targetHardwareProfile: String = "unknown_hardware",
            realtimeObstacleProviderType: ObstacleProviderType = ObstacleProviderType.DETECTION_MODEL,
        ): PerceptionConfig = PerceptionConfig(
            runtimeProfileName = runtimeProfileName,
            targetHardwareProfile = targetHardwareProfile,
            profile = profile,
            realtimeObstacleProviderType = realtimeObstacleProviderType,
        )
    }
}
