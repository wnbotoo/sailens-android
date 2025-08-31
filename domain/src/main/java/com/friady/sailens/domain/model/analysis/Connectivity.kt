package com.friady.sailens.domain.model.analysis

import com.friady.sailens.domain.model.common.DirectionBias
import com.friady.sailens.domain.model.common.Severity

/**
 * 连通性分析结果
 */
data class WalkPathConnectivity(
    // 布尔判定
    val isBlocked: Boolean,
    val isNarrowing: Boolean,
    val suggestedBias: DirectionBias?,

    // 置信度 (0. 0~1.0)
    val blockageConfidence: Float,
    val narrowingConfidence: Float,

    // 严重程度
    val blockageSeverity: Severity,
    val narrowingSeverity: Severity,

    // 统计数据
    val verticalReachRatio: Float,
    val validLayers: Int,
    val totalLayers: Int,
    val widthRetentionAvg: Float,
    val widthRetentionP25: Float,
    val widthSlope: Float,
    val floodReachRatio: Float,
    val floodWidthRetentionP25: Float,
    val floodVisitedRatio: Float,
)
