package com.friady.sailens.domain.config

/**
 * 分析配置
 */
data class AnalysisConfig(
    // 事件输出策略
    val enableNarrowingEvents: Boolean = false,
    val enableDirectionAdviceEvents: Boolean = false,

    // 连通性 - 分层扫描
    val sampleLayerRatios: List<Float> = listOf(0.85f, 0.70f, 0.55f),
    val minRunWidthRatio: Float = 0.05f,
    val reachRatioThreshold: Float = 0.40f,

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

    // 地面类型检测
    val groundTypeDominantThreshold: Float = 0.30f,

    // 稳定器参数
    val blockDebounceFrames: Int = 4,
    val narrowDebounceFrames: Int = 5,
    val biasDebounceFrames: Int = 3,
    val onRoadDebounceFrames: Int = 4,
    val groundTypeDebounceFrames: Int = 3,
    val intersectionDebounceFrames: Int = 3,
    val roadRatioSmoothWindow: Int = 5,
    val trafficLightDebounceFrames: Int = 2,
)
