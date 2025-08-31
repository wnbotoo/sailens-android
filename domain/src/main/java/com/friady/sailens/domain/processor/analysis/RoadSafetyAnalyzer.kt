package com.friady.sailens.domain.processor.analysis

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.analysis.RoadSafetyState
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.DetectedObstacle
import com.friady.sailens.domain.model.perception.SegmentationAnalysis
import com.friady.sailens.domain.util.BooleanStabilizer

/**
 * 道路安全分析器
 */
class RoadSafetyAnalyzer(
    private val config: AnalysisConfig,
    private val classMapper: ClassMapper,
) {
    private val onRoadStabilizer = BooleanStabilizer(config.onRoadDebounceFrames)

    fun analyze(
        analysis: SegmentationAnalysis,
        obstacles: List<DetectedObstacle>,
    ): RoadSafetyState {
        val isOnRoadRaw = analysis.bottomCenterRoadRatio > config.roadBottomCenterRatio
        val isOnRoad = onRoadStabilizer.update(isOnRoadRaw)

        val hasVehicleOnRoad = checkVehicleOnRoad(obstacles, analysis)

        val (isDangerous, dangerConfidence) = evaluateDanger(
            isOnRoad = isOnRoad,
            roadRatio = analysis.roadRatio,
            hasVehicle = hasVehicleOnRoad,
            hasTrafficLight = analysis.hasTrafficLight
        )

        return RoadSafetyState(
            isOnRoad = isOnRoad,
            isDangerous = isDangerous,
            roadRatio = analysis.roadRatio,
            hasVehicleOnRoad = hasVehicleOnRoad,
            hasTrafficLight = analysis.hasTrafficLight,
            dangerConfidence = dangerConfidence
        )
    }

    /**
     * 检查是否有车辆在道路上
     * 使用 classMapper 判断障碍物底部是否在道路上
     */
    private fun checkVehicleOnRoad(
        obstacles: List<DetectedObstacle>,
        analysis: SegmentationAnalysis,
    ): Boolean {
        return obstacles.any { obstacle ->
            obstacle.category == ObstacleCategory.VEHICLE &&
                    obstacle.isStable(minFrames = 2) &&
                    isRoadAt(analysis, obstacle.boundingBox.centerX, obstacle.boundingBox.maxY)
        }
    }

    /**
     * 点查询：是否是道路
     */
    private fun isRoadAt(
        analysis: SegmentationAnalysis,
        normalizedX: Float,
        normalizedY: Float,
    ): Boolean {
        val x = (normalizedX * analysis.width).toInt().coerceIn(0, analysis.width - 1)
        val y = (normalizedY * analysis.height).toInt().coerceIn(0, analysis.height - 1)
        val classId = analysis.segmentation.getClassId(x, y)
        return classMapper.isRoad(classId)
    }

    private fun evaluateDanger(
        isOnRoad: Boolean,
        roadRatio: Float,
        hasVehicle: Boolean,
        hasTrafficLight: Boolean,
    ): Pair<Boolean, Float> {
        if (!isOnRoad) return Pair(false, 0f)

        var confidence = 0f

        if (hasVehicle) {
            confidence += 0.6f
        }

        if (roadRatio > config.roadMediumRatioThreshold && hasTrafficLight) {
            confidence += 0.3f
        }

        if (roadRatio > config.roadHighRatioThreshold) {
            confidence += 0.4f
        }

        confidence = confidence.coerceIn(0f, 1f)
        val isDangerous = confidence >= 0.4f

        return Pair(isDangerous, confidence)
    }

    fun reset() {
        onRoadStabilizer.reset()
    }
}