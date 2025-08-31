package com.friady.sailens.domain.config

import com.friady.sailens.domain.model.common.InstanceProviderType
import com.friady.sailens.domain.model.common.PerceptionMode
import com.friady.sailens.domain.model.common.ZoneMode


/**
 * 感知配置
 */
data class PerceptionConfig(
    val mode: PerceptionMode = PerceptionMode.SEMANTIC_ONLY,
    val instanceProviderType: InstanceProviderType = InstanceProviderType.NONE,

    val enableHardwareDepth: Boolean = false,
    val enableMonocularDepth: Boolean = false,

    val minObstacleAreaRatio: Float = 0.005f,
    val maxObstacles: Int = 10,
    val minObstacleConfidence: Float = 0.4f,

    val zoneMode: ZoneMode = ZoneMode.THREE,

    val trackerIoUThreshold: Float = 0.3f,
    val trackerMaxMissedFrames: Int = 5,
    val trackerMinStableFrames: Int = 3,
)