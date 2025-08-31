package com.friady.sailens.domain.processor.analysis

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.analysis.GroundTypeChange
import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.util.EnumStabilizer

/**
 * 地面类型检测器
 */
class GroundTypeDetector(
    private val config: AnalysisConfig,
) {
    private val groundTypeStabilizer = EnumStabilizer(
        requiredFrames = config.groundTypeDebounceFrames,
        defaultValue = GroundType.UNKNOWN
    )
    private var lastStableType: GroundType = GroundType.UNKNOWN

    fun detect(distribution: Map<GroundType, Float>): GroundTypeChange? {
        val dominantType = findDominantType(distribution)
        val stableType = groundTypeStabilizer.update(dominantType)

        val change = if (stableType != lastStableType &&
            lastStableType != GroundType.UNKNOWN &&
            stableType != GroundType.UNKNOWN
        ) {
            GroundTypeChange(from = lastStableType, to = stableType)
        } else {
            null
        }

        if (stableType != GroundType.UNKNOWN) {
            lastStableType = stableType
        }

        return change
    }

    private fun findDominantType(distribution: Map<GroundType, Float>): GroundType {
        if (distribution.isEmpty()) return GroundType.UNKNOWN

        val dominant = distribution.maxByOrNull { it.value }

        return if (dominant != null && dominant.value > config.groundTypeDominantThreshold) {
            dominant.key
        } else {
            GroundType.UNKNOWN
        }
    }

    fun getCurrentType(): GroundType = lastStableType

    fun reset() {
        groundTypeStabilizer.reset()
        lastStableType = GroundType.UNKNOWN
    }
}