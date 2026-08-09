package com.sailens.domain.usecase.scene

import com.sailens.domain.processor.analysis.ConnectivityAnalysisProcessor
import com.sailens.domain.processor.analysis.FrameQualityAnalyzer
import com.sailens.domain.processor.analysis.GroundTypeDetector
import com.sailens.domain.processor.analysis.RoadSafetyAnalyzer
import com.sailens.domain.processor.analysis.SceneClassifier
import com.sailens.domain.processor.decision.CooldownManager
import com.sailens.domain.processor.perception.ObstacleTracker
import com.sailens.domain.processor.perception.SegmentationAnalysisProcessor
import com.sailens.domain.repository.ObstacleProvider
import com.sailens.domain.repository.PerceptionRepository
import com.sailens.domain.service.LogService
import com.sailens.domain.usecase.perception.ProcessFrameUseCase

/**
 * 停止导航用例
 */
class StopSceneAnalysisUseCase(
    private val perceptionRepository: PerceptionRepository,
    private val realtimeObstacleProvider: ObstacleProvider,
    private val processFrameUseCase: ProcessFrameUseCase,
    private val segmentationAnalyzer: SegmentationAnalysisProcessor,
    private val obstacleTracker: ObstacleTracker,
    private val connectivityChecker: ConnectivityAnalysisProcessor,
    private val roadSafetyAnalyzer: RoadSafetyAnalyzer,
    private val groundTypeDetector: GroundTypeDetector,
    private val sceneClassifier: SceneClassifier,
    private val frameQualityAnalyzer: FrameQualityAnalyzer,
    private val cooldownManager: CooldownManager,
    private val logService: LogService,
) {
    operator fun invoke() {
        logService.info("Navigation", "Navigation stopped")
        resetProcessors()
    }

    private fun resetProcessors() {
        processFrameUseCase.reset()
        segmentationAnalyzer.reset()
        obstacleTracker.reset()
        connectivityChecker.reset()
        roadSafetyAnalyzer.reset()
        groundTypeDetector.reset()
        sceneClassifier.reset()
        frameQualityAnalyzer.reset()
        cooldownManager.reset()
    }

    suspend fun release() {
        perceptionRepository.release()
        realtimeObstacleProvider.release()
        logService.info("Navigation", "Resources released")
    }
}
