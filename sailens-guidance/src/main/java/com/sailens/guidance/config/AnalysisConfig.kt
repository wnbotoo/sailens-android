package com.sailens.guidance.config

/**
 * 分析配置
 *
 * ## 比例阈值相对的是什么
 *
 * 语义 mask 只覆盖相机画面，不含 letterbox 填充（见 `SemanticContentRegion`）。此前 mask 带着
 * 填充：16:9 的帧放进 640x640，竖屏时左右、横屏时上下各有 140 像素填充，模型把填充判成
 * building（不可行走、非道路）。裁掉填充后：
 *
 * - **横向宽度阈值**（[minRunWidthRatio]、[segmentationCenterRatio]）是**画面长边**的比例
 *   （[widthFractionOfLongSide] 换算成当前 mask 宽度的比例）。旧 mask 宽度 640 恰好就是长边，
 *   所以数值不变、横竖屏的旧行为都原样保留；这在物理上也成立——同一条路在横屏画面里占的宽度比例
 *   本来就更小（水平视场更宽）。
 * - **面积阈值**（标注"面积等效"）按 16/9 折算：无论横竖屏，16:9 画面都只占旧 mask 的 9/16。
 * - 纵向比例不受影响。
 *
 * 驱动默认关闭提示的阈值（道路警告、地面变化、路口兜底）没有折算，开启前需按实测重调。
 */
data class AnalysisConfig(
    // 事件输出策略
    val enableNarrowingEvents: Boolean = false,
    /** 障碍物在近距离时加紧迫前缀（"注意，前方有人"）。 */
    val enableProximityPrefix: Boolean = true,
    /** 镜头遮挡 / 环境过暗的状态提示。用户无法自行察觉这类失效，默认必开。 */
    val enableSensorQualityEvents: Boolean = true,
    val enableRoadWarningEvents: Boolean = false,
    val enableGroundChangeEvents: Boolean = false,
    val enableIntersectionEvents: Boolean = true,
    val enableTrafficLightEvents: Boolean = true,

    // 连通性 - 分层扫描
    val sampleLayerRatios: List<Float> = listOf(0.85f, 0.70f, 0.55f),
    /** 画面长边的比例（见类注释）。 */
    val minRunWidthRatio: Float = 0.05f,
    val reachRatioThreshold: Float = 0.40f,
    val connectivityBottomRatio: Float = 0.15f,

    // 连通性 - 洪泛
    val floodWindowTopRatio: Float = 0.30f,
    val minFloodReachRatio: Float = 0.22f,
    val maxFloodNodes: Int = 30000,
    val floodEarlyStopReachRatio: Float = 0.70f,
    val floodEarlyStopWidthRetention: Float = 0.80f,

    // 阻塞/收窄阈值
    val blockageThreshold: Float = 0.65f,
    val narrowingThreshold: Float = 0.65f,
    val narrowEnterP25: Float = 0.55f,
    val narrowExitP25: Float = 0.70f,
    val narrowSlopeThreshold: Float = -0.08f,
    val connectivityPerspectiveHorizonY: Float = 0.35f,
    val connectivityPerspectiveMinWidthScale: Float = 0.50f,

    // 方向建议
    val directionBiasThreshold: Float = 0.15f,

    // 路口检测
    val intersectionRoadRatioThreshold: Float = 0.15f,
    val enableIntersectionFallback: Boolean = false,

    // 道路安全
    val roadHighRatioThreshold: Float = 0.70f,
    val roadMediumRatioThreshold: Float = 0.50f,
    val roadBottomCenterRatio: Float = 0.30f,
    val roadAreaWarningConfidence: Float = 0.35f,
    val rawVehicleOnRoadMinConfidence: Float = 0.60f,
    val rawVehicleOnRoadMinAreaRatio: Float = 0.006f,
    val rawVehicleOnRoadMinBottomY: Float = 0.35f,
    val trackedVehicleOnRoadMinStableFrames: Int = 3,
    val trackedVehicleOnRoadMinConfidence: Float = 0.55f,
    val trackedVehicleOnRoadMinAreaRatio: Float = 0.008f,
    val trackedVehicleOnRoadMinBottomY: Float = 0.45f,
    val vehicleOnRoadMinRoadSamples: Int = 2,
    val vehicleOnRoadCenterBandWidthRatio: Float = 0.40f,
    val vehicleOnRoadMinCenterBandOverlapRatio: Float = 0.50f,
    val vehicleOnRoadNearBottomY: Float = 0.65f,

    // 地面类型检测
    val groundTypeDominantThreshold: Float = 0.30f,

    // 语义统计区域
    val segmentationBottomRatio: Float = 0.20f,
    /** 画面长边的比例（见类注释）。 */
    val segmentationCenterRatio: Float = 0.40f,
    val segmentationNavigationRegionRatio: Float = 0.45f,

    // 稳定器参数
    val blockDebounceFrames: Int = 4,
    val narrowDebounceFrames: Int = 5,
    val biasDebounceFrames: Int = 3,
    val onRoadDebounceFrames: Int = 4,
    val groundTypeDebounceFrames: Int = 3,
    val intersectionDebounceFrames: Int = 3,
    val roadRatioSmoothWindow: Int = 5,
    val trafficLightDebounceFrames: Int = 4,
    /** 面积等效：原 0.0015 x letterbox mask 面积。 */
    val trafficLightMinPixelRatio: Float = 0.00267f,
    /** 面积等效：原 0.08 x letterbox mask 面积（填充不是道路，道路占比按 16/9 放大）。 */
    val trafficLightMinRoadRatio: Float = 0.142f,
    val rawVehicleOnRoadDebounceFrames: Int = 3,
)

/**
 * A horizontal threshold given as a fraction of the frame's long side, as a fraction of this
 * [width]-wide mask. For a portrait 360x640 mask 0.05 becomes 0.089; for a landscape 640x360
 * mask it stays 0.05 -- the effective values the letterboxed mask always had.
 */
fun widthFractionOfLongSide(fraction: Float, width: Int, height: Int): Float {
    if (width <= 0) return fraction
    return (fraction * maxOf(width, height) / width).coerceIn(0f, 1f)
}
