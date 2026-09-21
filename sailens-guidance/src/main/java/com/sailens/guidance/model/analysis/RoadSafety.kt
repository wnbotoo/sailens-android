package com.sailens.guidance.model.analysis

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
    val vehicleOnRoadSource: VehicleOnRoadSource = VehicleOnRoadSource.NONE,
    val vehicleOnRoadConfidence: Float = 0f,
    val vehicleOnRoadReason: VehicleOnRoadReason = VehicleOnRoadReason.NONE,
    val vehicleOnRoadBottomY: Float = 0f,
    val vehicleOnRoadCenterBandOverlap: Float = 0f,
    val vehicleOnRoadAreaRatio: Float = 0f,
)

enum class VehicleOnRoadSource {
    NONE,
    RAW,
    TRACKED,
    RAW_AND_TRACKED,
}

enum class VehicleOnRoadReason {
    NONE,
    NEAR_BOTTOM,
    CENTER_BAND,
}
