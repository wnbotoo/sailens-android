package com.friady.sailens.domain.processor.analysis

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.analysis.RoadSafetyState
import com.friady.sailens.domain.model.common.NormalizedRect
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.DetectedInstance
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

    private companion object {
        private const val MIN_RAW_VEHICLE_CONFIDENCE = 0.50f
        private const val MIN_RAW_VEHICLE_AREA_RATIO = 0.003f
        private const val MIN_VEHICLE_BOTTOM_Y = 0.25f
    }

    fun analyze(
        analysis: SegmentationAnalysis,
        obstacles: List<DetectedObstacle>,
        instanceDetections: List<DetectedInstance> = emptyList(),
    ): RoadSafetyState {
        val isOnRoadRaw = analysis.bottomCenterRoadRatio > config.roadBottomCenterRatio
        val isOnRoad = onRoadStabilizer.update(isOnRoadRaw)

        val hasVehicleOnRoad = checkVehicleOnRoad(
            obstacles = obstacles,
            instanceDetections = instanceDetections,
            analysis = analysis,
        )

        val (isDangerous, dangerConfidence) = evaluateDanger(
            isOnRoad = isOnRoad,
            roadRatio = analysis.roadRatio,
            bottomCenterRoadRatio = analysis.bottomCenterRoadRatio,
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
        instanceDetections: List<DetectedInstance>,
        analysis: SegmentationAnalysis,
    ): Boolean {
        val trackedVehicleOnRoad = obstacles.any { obstacle ->
            obstacle.category == ObstacleCategory.VEHICLE &&
                obstacle.isStable(minFrames = 1) &&
                isVehicleBaseOnRoad(analysis, obstacle.boundingBox)
        }

        if (trackedVehicleOnRoad) return true

        return instanceDetections.any { detection ->
            detection.category == ObstacleCategory.VEHICLE &&
                detection.confidence >= MIN_RAW_VEHICLE_CONFIDENCE &&
                detection.boundingBox.area >= MIN_RAW_VEHICLE_AREA_RATIO &&
                detection.boundingBox.maxY >= MIN_VEHICLE_BOTTOM_Y &&
                isVehicleBaseOnRoad(analysis, detection.boundingBox)
        }
    }

    /**
     * 点查询：是否是道路
     */
    private fun isVehicleBaseOnRoad(
        analysis: SegmentationAnalysis,
        boundingBox: NormalizedRect,
    ): Boolean {
        val sampleXs = floatArrayOf(
            boundingBox.x + boundingBox.width * 0.25f,
            boundingBox.centerX,
            boundingBox.x + boundingBox.width * 0.75f,
        )
        val sampleYs = floatArrayOf(
            boundingBox.maxY,
            boundingBox.maxY + 0.02f,
            boundingBox.maxY + 0.05f,
        )

        for (sampleY in sampleYs) {
            for (sampleX in sampleXs) {
                if (isRoadAt(analysis, sampleX, sampleY)) {
                    return true
                }
            }
        }
        return false
    }

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
        bottomCenterRoadRatio: Float,
        hasVehicle: Boolean,
        hasTrafficLight: Boolean,
    ): Pair<Boolean, Float> {
        if (!isOnRoad) return Pair(false, 0f)

        if (hasVehicle) {
            val confidence = (config.roadAreaWarningConfidence + 0.55f).coerceIn(0f, 1f)
            return Pair(true, confidence)
        }

        if (roadRatio > config.roadMediumRatioThreshold && hasTrafficLight) {
            val confidence = (config.roadAreaWarningConfidence + 0.25f).coerceIn(0f, 1f)
            return Pair(confidence >= 0.5f, confidence)
        }

        val isClearlyInsideRoad = roadRatio > config.roadHighRatioThreshold &&
            bottomCenterRoadRatio > config.roadBottomCenterRatio * 1.8f
        if (isClearlyInsideRoad) {
            val confidence = (config.roadAreaWarningConfidence + 0.20f).coerceIn(0f, 1f)
            return Pair(confidence >= 0.55f, confidence)
        }

        return Pair(false, config.roadAreaWarningConfidence)
    }

    fun reset() {
        onRoadStabilizer.reset()
    }
}
