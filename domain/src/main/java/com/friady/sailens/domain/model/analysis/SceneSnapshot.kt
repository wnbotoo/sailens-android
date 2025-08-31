package com.friady.sailens.domain.model.analysis

import com.friady.sailens.domain.model.perception.DetectedObstacle

/**
 * 场景快照
 */
data class SceneSnapshot(
    val timestamp: Long,
    val obstacles: List<DetectedObstacle>,
    val bottomCoverage: Float,
    val connectivity: WalkPathConnectivity,
    val sceneElements: SceneElements,
    val roadSafety: RoadSafetyState,
    val groundTypeChange: GroundTypeChange?,
)

/**
 * 场景元素
 */
data class SceneElements(
    val hasIntersection: Boolean = false,
    val hasCrosswalk: Boolean = false,
    val hasTactilePaving: Boolean = false,
    val hasTrafficLight: Boolean = false,
)