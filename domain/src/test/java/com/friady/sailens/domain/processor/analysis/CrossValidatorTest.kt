package com.friady.sailens.domain.processor.analysis

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.analysis.RoadSafetyState
import com.friady.sailens.domain.model.analysis.WalkPathConnectivity
import com.friady.sailens.domain.model.common.Severity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossValidatorTest {
    private val validator = CrossValidator(AnalysisConfig())

    @Test
    fun `suppresses blocked when on road and corridor is still connected`() {
        val connectivity = baseConnectivity(
            isBlocked = true,
            blockageConfidence = 0.53f,
            blockageSeverity = Severity.MODERATE,
            verticalReachRatio = 0.45f,
            floodReachRatio = 0.24f,
            widthRetentionP25 = 0.72f,
        )
        val roadSafety = RoadSafetyState(
            isOnRoad = true,
            isDangerous = false,
            roadRatio = 0.6f,
            hasVehicleOnRoad = false,
            hasTrafficLight = false,
            dangerConfidence = 0f,
        )

        val result = validator.validate(connectivity, roadSafety, groundChange = null)

        assertFalse(result.connectivity.isBlocked)
        assertTrue(result.connectivity.blockageConfidence < 0.3f)
    }

    @Test
    fun `keeps blocked when road corridor is connected but too narrow`() {
        val connectivity = baseConnectivity(
            isBlocked = true,
            blockageConfidence = 0.53f,
            blockageSeverity = Severity.MODERATE,
            verticalReachRatio = 0.45f,
            floodReachRatio = 0.24f,
            widthRetentionP25 = 0.20f,
        )
        val roadSafety = RoadSafetyState(
            isOnRoad = true,
            isDangerous = false,
            roadRatio = 0.6f,
            hasVehicleOnRoad = false,
            hasTrafficLight = false,
            dangerConfidence = 0f,
        )

        val result = validator.validate(connectivity, roadSafety, groundChange = null)

        assertTrue(result.connectivity.isBlocked)
    }

    @Test
    fun `keeps blocked when connectivity is truly poor even on road`() {
        val connectivity = baseConnectivity(
            isBlocked = true,
            blockageConfidence = 0.82f,
            blockageSeverity = Severity.SEVERE,
            verticalReachRatio = 0.10f,
            floodReachRatio = 0.05f,
            widthRetentionP25 = 0.08f,
        )
        val roadSafety = RoadSafetyState(
            isOnRoad = true,
            isDangerous = true,
            roadRatio = 0.8f,
            hasVehicleOnRoad = true,
            hasTrafficLight = false,
            dangerConfidence = 0.6f,
        )

        val result = validator.validate(connectivity, roadSafety, groundChange = null)

        assertTrue(result.connectivity.isBlocked)
    }

    private fun baseConnectivity(
        isBlocked: Boolean,
        blockageConfidence: Float,
        blockageSeverity: Severity,
        verticalReachRatio: Float,
        floodReachRatio: Float,
        widthRetentionP25: Float,
    ) = WalkPathConnectivity(
        isBlocked = isBlocked,
        isNarrowing = false,
        suggestedBias = null,
        blockageConfidence = blockageConfidence,
        narrowingConfidence = 0f,
        blockageSeverity = blockageSeverity,
        narrowingSeverity = Severity.NONE,
        verticalReachRatio = verticalReachRatio,
        validLayers = 2,
        totalLayers = 3,
        widthRetentionAvg = widthRetentionP25,
        widthRetentionP25 = widthRetentionP25,
        widthSlope = 0f,
        floodReachRatio = floodReachRatio,
        floodWidthRetentionP25 = widthRetentionP25,
        floodVisitedRatio = floodReachRatio,
    )
}
