package com.friady.sailens.domain.config

import com.friady.sailens.domain.model.common.InferenceStrategy
import com.friady.sailens.domain.model.common.InstanceProviderType
import com.friady.sailens.domain.model.common.PerceptionMode
import com.friady.sailens.domain.model.common.SemanticProviderType
import com.friady.sailens.domain.model.common.ZoneMode


/**
 * 感知配置
 *
 * 双模型职责：
 *   - YOLOv26n-sem (semanticProviderType = YOLO26_SEM)
 *       理解可行走区域：哪里能走、地面类型、道路边界
 *       → 驱动 PerceptionRepository.segment() → SegmentationAnalysis
 *
 *   - YOLOv26n-seg (instanceProviderType = YOLO26_SEG)
 *       识别障碍物：有什么（类别）、在哪里（BBox + Mask）
 *       → 驱动 InstanceSegmentationProvider.detect() → InstanceSegmentationOutput
 *
 * 推理策略 (inferenceStrategy)：
 *   - SIMULTANEOUS: 两个模型每帧同时推理，信息最新，适合高性能设备
 *   - ALTERNATING:  seg 奇偶帧交替，tracker 补偿偶数帧，适合低功耗场景
 *
 * semanticFrameSkipping:
 *   - enableSemanticFrameSkipping = true 时，语义分割可按 semanticFrameInterval 降频运行
 *   - 跳过帧会复用最近一次 semantic mask，适合当前 YOLO26-sem 高分辨率输出较重的场景
 */
data class PerceptionConfig(
    val mode: PerceptionMode = PerceptionMode.COMBINED,
    val semanticProviderType: SemanticProviderType = SemanticProviderType.YOLO26_SEM,
    val instanceProviderType: InstanceProviderType = InstanceProviderType.YOLO26_SEG,

    /** 双模型推理策略，仅 mode = COMBINED 时生效 */
    val inferenceStrategy: InferenceStrategy = InferenceStrategy.ALTERNATING,

    /** 是否允许语义分割降频运行；关闭后 semantic 每帧推理 */
    val enableSemanticFrameSkipping: Boolean = false,

    /** semantic 每隔多少帧刷新一次，2 表示一帧推理、一帧复用 */
    val semanticFrameInterval: Int = 2,

    val enableHardwareDepth: Boolean = false,
    val enableMonocularDepth: Boolean = false,

    val minObstacleAreaRatio: Float = 0.005f,
    val maxObstacles: Int = 10,
    val minObstacleConfidence: Float = 0.4f,
    val navigationCorridorCenterWidth: Float = 0.50f,
    val semanticObstacleMinBottomY: Float = 0.45f,
    val semanticObstacleMinCorridorOverlapRatio: Float = 0.12f,
    val staticObstacleMinBottomY: Float = 0.55f,
    val staticObstacleMinCorridorOverlapRatio: Float = 0.20f,
    val maxBackgroundObstacleAreaRatio: Float = 0.35f,

    val zoneMode: ZoneMode = ZoneMode.THREE,

    val trackerIoUThreshold: Float = 0.3f,
    val trackerMaxMissedFrames: Int = 5,
    val trackerMinStableFrames: Int = 3,
)
