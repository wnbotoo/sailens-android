package com.friady.sailens.domain.usecase.scene

import com.friady.sailens.domain.processor.analysis.ConnectivityChecker
import com.friady.sailens.domain.processor.analysis.GroundTypeDetector
import com.friady.sailens.domain.processor.analysis.RoadSafetyAnalyzer
import com.friady.sailens.domain.processor.analysis.SceneClassifier
import com.friady.sailens.domain.processor.decision.CooldownManager
import com.friady.sailens.domain.processor.decision.EventGenerator
import com.friady.sailens.domain.processor.perception.ObstacleTracker
import com.friady.sailens.domain.processor.perception.SegmentationAnalyzer
import com.friady.sailens.domain.repository.InstanceSegmentationProvider
import com.friady.sailens.domain.repository.PerceptionRepository
import com.friady.sailens.domain.service.LogService

/**
 * 停止导航用例
 */
class StopSceneAnalysisUseCase(
    private val perceptionRepository: PerceptionRepository,
    private val instanceProvider: InstanceSegmentationProvider,
    private val segmentationAnalyzer: SegmentationAnalyzer,
    private val obstacleTracker: ObstacleTracker,
    private val connectivityChecker: ConnectivityChecker,
    private val roadSafetyAnalyzer: RoadSafetyAnalyzer,
    private val groundTypeDetector: GroundTypeDetector,
    private val sceneClassifier: SceneClassifier,
    private val eventGenerator: EventGenerator,
    private val cooldownManager: CooldownManager,
    private val logService: LogService,
) {
    operator fun invoke() {
        logService.info("Navigation", "Navigation stopped")
        resetProcessors()
    }

    private fun resetProcessors() {
        segmentationAnalyzer.reset()
        obstacleTracker.reset()
        connectivityChecker.reset()
        roadSafetyAnalyzer.reset()
        groundTypeDetector.reset()
        sceneClassifier.reset()
        eventGenerator.reset()
        cooldownManager.reset()
    }

    suspend fun release() {
        perceptionRepository.release()
        instanceProvider.release()
        logService.info("Navigation", "Resources released")
    }
}
