package com.friady.sailens.domain.usecase.perception

import com.friady.sailens.domain.model.analysis.SceneSnapshot
import com.friady.sailens.domain.model.perception.PerceptionResult
import com.friady.sailens.domain.processor.analysis.ConnectivityChecker
import com.friady.sailens.domain.processor.analysis.CrossValidator
import com.friady.sailens.domain.processor.analysis.GroundTypeDetector
import com.friady.sailens.domain.processor.analysis.RoadSafetyAnalyzer
import com.friady.sailens.domain.processor.analysis.SceneClassifier

/**
 * 分析场景用例
 */
class AnalyzeSceneUseCase(
    private val connectivityChecker: ConnectivityChecker,
    private val roadSafetyAnalyzer: RoadSafetyAnalyzer,
    private val groundTypeDetector: GroundTypeDetector,
    private val sceneClassifier: SceneClassifier,
    private val crossValidator: CrossValidator,
) {
    operator fun invoke(perceptionResult: PerceptionResult): SceneSnapshot {
        val analysis = perceptionResult.analysis

        // 1.  独立分析
        val connectivity = connectivityChecker.analyze(analysis.passableMask)
        val roadSafety = roadSafetyAnalyzer.analyze(analysis, perceptionResult.obstacles)
        val groundChange = groundTypeDetector.detect(analysis.bottomCenterGroundDistribution)
        val sceneElements = sceneClassifier.classify(analysis)

        // 2. 交叉验证
        val validated = crossValidator.validate(connectivity, roadSafety, groundChange)

        return SceneSnapshot(
            timestamp = perceptionResult.timestamp,
            obstacles = perceptionResult.obstacles,
            bottomCoverage = perceptionResult.bottomStats.coverage,
            connectivity = validated.connectivity,
            sceneElements = sceneElements,
            roadSafety = validated.roadSafety,
            groundTypeChange = validated.groundChange
        )
    }
}