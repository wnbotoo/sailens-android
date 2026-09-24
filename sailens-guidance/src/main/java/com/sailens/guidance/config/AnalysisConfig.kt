package com.sailens.guidance.config

/**
 * 分析配置
 *
 * ## 比例阈值是"相机画面"的比例
 *
 * 语义 mask 只覆盖相机画面，不含 letterbox 填充（见 `SemanticContentRegion`）。此前 mask 带着
 * 填充：竖屏 540x960 帧在 640x640 输入里左右各有 140 列填充，画面只占 360/640 = 9/16 的宽度，
 * 填充被模型判成 building（不可行走、非道路）。裁掉填充后，所有"占 mask 宽度/面积的比例"都按
 * 16/9 放大，为了保持实测过的竖屏行为不变，以"宽度比例"或"面积比例"表达、且驱动**已开启**
 * 提示的阈值按 16/9 重新折算（标注"竖屏等效"）。纵向比例不受影响（竖屏上下没有填充）。
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
    /** 竖屏等效：原 0.05 x mask 宽度 = 画面宽度的 0.089。 */
    val minRunWidthRatio: Float = 0.089f,
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
    /** 竖屏等效：原 0.40 x mask 宽度 = 画面宽度的 0.71。 */
    val segmentationCenterRatio: Float = 0.71f,
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
    /** 竖屏等效：原 0.0015 x mask 面积。 */
    val trafficLightMinPixelRatio: Float = 0.00267f,
    /** 竖屏等效：原 0.08 x mask 面积（填充不是道路，道路占比按 16/9 放大）。 */
    val trafficLightMinRoadRatio: Float = 0.142f,
    val rawVehicleOnRoadDebounceFrames: Int = 3,
)
