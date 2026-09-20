package com.sailens.guidance.usecase.perception

import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.perception.PerceptionResult
import com.sailens.guidance.processor.analysis.ConnectivityAnalysisProcessor
import com.sailens.guidance.processor.analysis.CrossValidator
import com.sailens.guidance.processor.analysis.GroundTypeDetector
import com.sailens.guidance.processor.analysis.ObstacleOcclusionAnalyzer
import com.sailens.guidance.processor.analysis.RoadSafetyAnalyzer
import com.sailens.guidance.processor.analysis.SceneClassifier

/**
 * 分析场景用例
 */
class AnalyzeSceneUseCase(
    private val connectivityChecker: ConnectivityAnalysisProcessor,
    private val roadSafetyAnalyzer: RoadSafetyAnalyzer,
    private val groundTypeDetector: GroundTypeDetector,
    private val sceneClassifier: SceneClassifier,
    private val crossValidator: CrossValidator,
    private val obstacleOcclusionAnalyzer: ObstacleOcclusionAnalyzer,
) {
    operator fun invoke(perceptionResult: PerceptionResult): SceneSnapshot {
        val analysis = perceptionResult.analysis

        // 1. 把跟踪障碍物 bbox 接地带从可行走区抠掉（det/sem 交叉验证，保守只减不增），
        //    再做连通性分析：障碍物挡住可行走区会自然体现为 blockage 上升。
        //    输入用跟踪轨迹而非当帧原始检测：det 间隔帧由预测补偿，信号逐帧连续不闪烁。
        val occlusion = obstacleOcclusionAnalyzer.analyze(
            passableMask = analysis.passableMask,
            obstacles = perceptionResult.obstacles,
        )

        // 2.  独立分析
        val connectivity = connectivityChecker.analyze(occlusion.effectivePassableMask)
        val roadSafety = roadSafetyAnalyzer.analyze(
            analysis = analysis,
            obstacles = perceptionResult.obstacles,
            obstacleDetections = perceptionResult.obstacleDetections,
        )
        val groundChange = groundTypeDetector.detect(analysis.bottomCenterGroundDistribution)
        val sceneElements = sceneClassifier.classify(analysis)

        // 3. 交叉验证
        val validated = crossValidator.validate(connectivity, roadSafety, groundChange)

        return SceneSnapshot(
            timestamp = perceptionResult.timestamp,
            obstacles = perceptionResult.obstacles,
            bottomCoverage = perceptionResult.bottomStats.coverage,
            connectivity = validated.connectivity,
            sceneElements = sceneElements,
            roadSafety = validated.roadSafety,
            groundTypeChange = validated.groundChange,
            occludedPassableRatio = occlusion.occludedPassableRatio,
        )
    }
}
