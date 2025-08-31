package com.friady.sailens.domain.processor.analysis

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.analysis.GroundTypeChange
import com.friady.sailens.domain.model.analysis.RoadSafetyState
import com.friady.sailens.domain.model.analysis.WalkPathConnectivity
import com.friady.sailens.domain.model.common.Severity

/**
 * 交叉验证器
 */
class CrossValidator(
    private val config: AnalysisConfig,
) {
    data class ValidatedResults(
        val connectivity: WalkPathConnectivity,
        val roadSafety: RoadSafetyState,
        val groundChange: GroundTypeChange?,
    )

    fun validate(
        connectivity: WalkPathConnectivity,
        roadSafety: RoadSafetyState,
        groundChange: GroundTypeChange?,
    ): ValidatedResults {
        var adjustedConnectivity = connectivity

        // 规则1：在道路上时，收窄判定更宽松
        if (roadSafety.isOnRoad && connectivity.isNarrowing) {
            if (connectivity.narrowingConfidence < 0.7f) {
                adjustedConnectivity = connectivity.copy(
                    isNarrowing = false,
                    narrowingSeverity = Severity.NONE
                )
            }
        }

        // 规则2：阻塞严重时，收窄无意义
        if (connectivity.blockageSeverity == Severity.SEVERE) {
            adjustedConnectivity = adjustedConnectivity.copy(
                isNarrowing = false,
                narrowingSeverity = Severity.NONE
            )
        }

        return ValidatedResults(
            connectivity = adjustedConnectivity,
            roadSafety = roadSafety,
            groundChange = groundChange
        )
    }
}