package com.sailens.guidance.usecase.scene

import com.sailens.guidance.processor.analysis.ConnectivityAnalysisProcessor
import com.sailens.guidance.processor.analysis.FrameQualityAnalyzer
import com.sailens.guidance.processor.analysis.GroundRecognitionAnalyzer
import com.sailens.guidance.processor.analysis.GroundTypeDetector
import com.sailens.guidance.processor.analysis.RoadSafetyAnalyzer
import com.sailens.guidance.processor.analysis.SceneClassifier
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.perception.ObstacleTracker
import com.sailens.guidance.processor.perception.SegmentationAnalysisProcessor
import com.sailens.guidance.repository.ObstacleProvider
import com.sailens.guidance.repository.PerceptionRepository
import com.sailens.core.log.LogService
import com.sailens.guidance.usecase.perception.ProcessFrameUseCase
import com.sailens.runtime.InputPreprocessCache

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
    private val groundRecognitionAnalyzer: GroundRecognitionAnalyzer,
    private val cooldownManager: CooldownManager,
    private val logService: LogService,
    private val preprocessCache: InputPreprocessCache? = null,
) {
    operator fun invoke() {
        logService.info("Navigation", "Navigation stopped")
        resetProcessors()
        preprocessCache?.clear()
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
        groundRecognitionAnalyzer.reset()
        cooldownManager.reset()
    }

    suspend fun release() {
        try {
            perceptionRepository.release()
            realtimeObstacleProvider.release()
        } finally {
            preprocessCache?.clear()
        }
        logService.info("Navigation", "Resources released")
    }
}
