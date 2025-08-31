package com.friady.sailens.domain.model.analysis

/**
 * 道路安全状态
 */
data class RoadSafetyState(
    val isOnRoad: Boolean,
    val isDangerous: Boolean,
    val roadRatio: Float,
    val hasVehicleOnRoad: Boolean,
    val hasTrafficLight: Boolean,
    val dangerConfidence: Float,
)