package com.sailens.domain.processor.analysis

import com.sailens.domain.config.AnalysisConfig
import com.sailens.domain.model.analysis.RoadSafetyState
import com.sailens.domain.model.analysis.VehicleOnRoadReason
import com.sailens.domain.model.analysis.VehicleOnRoadSource
import com.sailens.core.geometry.NormalizedRect
import com.sailens.domain.model.common.ObstacleCategory
import com.sailens.domain.semantics.NavigationSemantics
import com.sailens.domain.model.perception.ObstacleDetection
import com.sailens.domain.model.perception.DetectedObstacle
import com.sailens.domain.model.perception.SegmentationAnalysis
import com.sailens.domain.util.BooleanStabilizer

/**
 * 道路安全分析器
 */
class RoadSafetyAnalyzer(
    private val config: AnalysisConfig,
    private val navigationSemantics: NavigationSemantics,
) {
    private val onRoadStabilizer = BooleanStabilizer(config.onRoadDebounceFrames)
    private val rawVehicleOnRoadStabilizer =
        BooleanStabilizer(config.rawVehicleOnRoadDebounceFrames)
    private var lastRawVehicleEvidence = VehicleEvidence()

    fun analyze(
        analysis: SegmentationAnalysis,
        obstacles: List<DetectedObstacle>,
        obstacleDetections: List<ObstacleDetection> = emptyList(),
    ): RoadSafetyState {
        val isOnRoadRaw = analysis.bottomCenterRoadRatio > config.roadBottomCenterRatio
        val isOnRoad = onRoadStabilizer.update(isOnRoadRaw)

        val vehicleEvidence = checkVehicleOnRoad(
            obstacles = obstacles,
            obstacleDetections = obstacleDetections,
            analysis = analysis,
        )

        val (isDangerous, dangerConfidence) = evaluateDanger(
            isOnRoad = isOnRoad,
            roadRatio = analysis.roadRatio,
            bottomCenterRoadRatio = analysis.bottomCenterRoadRatio,
            hasVehicle = vehicleEvidence.source != VehicleOnRoadSource.NONE,
            hasTrafficLight = analysis.hasTrafficLight
        )

        return RoadSafetyState(
            isOnRoad = isOnRoad,
            isDangerous = isDangerous,
            roadRatio = analysis.roadRatio,
            hasVehicleOnRoad = vehicleEvidence.source != VehicleOnRoadSource.NONE,
            hasTrafficLight = analysis.hasTrafficLight,
            dangerConfidence = dangerConfidence,
            vehicleOnRoadSource = vehicleEvidence.source,
            vehicleOnRoadConfidence = vehicleEvidence.confidence,
            vehicleOnRoadReason = vehicleEvidence.reason,
            vehicleOnRoadBottomY = vehicleEvidence.bottomY,
            vehicleOnRoadCenterBandOverlap = vehicleEvidence.centerBandOverlap,
            vehicleOnRoadAreaRatio = vehicleEvidence.areaRatio,
        )
    }

    /**
     * 检查是否有车辆在道路上
     * 使用 navigationSemantics 判断障碍物底部是否在道路上
     */
    private fun checkVehicleOnRoad(
        obstacles: List<DetectedObstacle>,
        obstacleDetections: List<ObstacleDetection>,
        analysis: SegmentationAnalysis,
    ): VehicleEvidence {
        val trackedVehicleEvidence = obstacles
            .filter { obstacle ->
                obstacle.category == ObstacleCategory.VEHICLE &&
                    obstacle.isStable(minFrames = config.trackedVehicleOnRoadMinStableFrames) &&
                    obstacle.confidence >= config.trackedVehicleOnRoadMinConfidence &&
                    obstacle.areaRatio >= config.trackedVehicleOnRoadMinAreaRatio &&
                    obstacle.boundingBox.maxY >= config.trackedVehicleOnRoadMinBottomY &&
                    roadVehicleReason(obstacle.boundingBox) != VehicleOnRoadReason.NONE &&
                    roadSampleHits(analysis, obstacle.boundingBox) >= config.vehicleOnRoadMinRoadSamples
            }
            .maxByOrNull { it.confidence }
            ?.let { obstacle ->
                VehicleEvidence(
                    source = VehicleOnRoadSource.TRACKED,
                    confidence = obstacle.confidence,
                    reason = roadVehicleReason(obstacle.boundingBox),
                    bottomY = obstacle.boundingBox.maxY,
                    centerBandOverlap = centerBandOverlapRatio(obstacle.boundingBox),
                    areaRatio = obstacle.areaRatio,
                )
            } ?: VehicleEvidence()

        val rawVehicleEvidence = obstacleDetections
            .filter { detection ->
                detection.category == ObstacleCategory.VEHICLE &&
                    detection.confidence >= config.rawVehicleOnRoadMinConfidence &&
                    detection.boundingBox.area >= config.rawVehicleOnRoadMinAreaRatio &&
                    detection.boundingBox.maxY >= config.rawVehicleOnRoadMinBottomY &&
                    roadVehicleReason(detection.boundingBox) != VehicleOnRoadReason.NONE &&
                    roadSampleHits(analysis, detection.boundingBox) >= config.vehicleOnRoadMinRoadSamples
            }
            .maxByOrNull { it.confidence }
            ?.let { detection ->
                VehicleEvidence(
                    source = VehicleOnRoadSource.RAW,
                    confidence = detection.confidence,
                    reason = roadVehicleReason(detection.boundingBox),
                    bottomY = detection.boundingBox.maxY,
                    centerBandOverlap = centerBandOverlapRatio(detection.boundingBox),
                    areaRatio = detection.boundingBox.area,
                )
            }

        if (rawVehicleEvidence != null) {
            lastRawVehicleEvidence = rawVehicleEvidence
        }
        val rawVehicleOnRoad = rawVehicleOnRoadStabilizer.update(rawVehicleEvidence != null)
        val debouncedRawVehicleEvidence = if (rawVehicleOnRoad) {
            lastRawVehicleEvidence.takeIf { it.source == VehicleOnRoadSource.RAW }
        } else {
            null
        }

        return combineVehicleEvidence(trackedVehicleEvidence, debouncedRawVehicleEvidence)
    }

    private fun roadVehicleReason(boundingBox: NormalizedRect): VehicleOnRoadReason {
        if (boundingBox.maxY >= config.vehicleOnRoadNearBottomY) {
            return VehicleOnRoadReason.NEAR_BOTTOM
        }
        return if (centerBandOverlapRatio(boundingBox) >= config.vehicleOnRoadMinCenterBandOverlapRatio) {
            VehicleOnRoadReason.CENTER_BAND
        } else {
            VehicleOnRoadReason.NONE
        }
    }

    private fun centerBandOverlapRatio(boundingBox: NormalizedRect): Float {
        if (boundingBox.width <= 0f) return 0f
        val bandHalfWidth = config.vehicleOnRoadCenterBandWidthRatio.coerceIn(0f, 1f) / 2f
        val bandLeft = (0.5f - bandHalfWidth).coerceIn(0f, 1f)
        val bandRight = (0.5f + bandHalfWidth).coerceIn(0f, 1f)
        val overlap = (minOf(boundingBox.maxX, bandRight) - maxOf(boundingBox.x, bandLeft))
            .coerceAtLeast(0f)
        return (overlap / boundingBox.width).coerceIn(0f, 1f)
    }

    private fun combineVehicleEvidence(
        tracked: VehicleEvidence,
        raw: VehicleEvidence?,
    ): VehicleEvidence {
        val hasTracked = tracked.source == VehicleOnRoadSource.TRACKED
        val rawEvidence = raw?.takeIf { it.source == VehicleOnRoadSource.RAW }
        return when {
            hasTracked && rawEvidence != null -> {
                val primaryEvidence = if (tracked.confidence >= rawEvidence.confidence) {
                    tracked
                } else {
                    rawEvidence
                }
                primaryEvidence.copy(
                    source = VehicleOnRoadSource.RAW_AND_TRACKED,
                    confidence = maxOf(tracked.confidence, rawEvidence.confidence),
                )
            }
            hasTracked -> tracked
            rawEvidence != null -> rawEvidence
            else -> VehicleEvidence()
        }
    }

    private fun roadSampleHits(
        analysis: SegmentationAnalysis,
        boundingBox: NormalizedRect,
    ): Int {
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

        var hits = 0
        for (sampleY in sampleYs) {
            for (sampleX in sampleXs) {
                if (isRoadAt(analysis, sampleX, sampleY)) {
                    hits++
                }
            }
        }
        return hits
    }

    private fun isRoadAt(
        analysis: SegmentationAnalysis,
        normalizedX: Float,
        normalizedY: Float,
    ): Boolean {
        val x = (normalizedX * analysis.width).toInt().coerceIn(0, analysis.width - 1)
        val y = (normalizedY * analysis.height).toInt().coerceIn(0, analysis.height - 1)
        val classId = analysis.segmentation.getClassId(x, y)
        return navigationSemantics.isRoad(classId)
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
        rawVehicleOnRoadStabilizer.reset()
        lastRawVehicleEvidence = VehicleEvidence()
    }

    private data class VehicleEvidence(
        val source: VehicleOnRoadSource = VehicleOnRoadSource.NONE,
        val confidence: Float = 0f,
        val reason: VehicleOnRoadReason = VehicleOnRoadReason.NONE,
        val bottomY: Float = 0f,
        val centerBandOverlap: Float = 0f,
        val areaRatio: Float = 0f,
    )
}
