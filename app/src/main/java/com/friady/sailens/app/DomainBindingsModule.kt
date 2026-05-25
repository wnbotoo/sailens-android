package com.friady.sailens.app

import com.friady.sailens.camera.CameraRuntimeConfig
import com.friady.sailens.data.source.ml.instance.YOLO26SegModelConfig
import com.friady.sailens.data.source.ml.semantic.YOLO26SemModelConfig
import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.config.PerceptionConfig
import com.friady.sailens.domain.config.PipelinePerformanceBudget
import com.friady.sailens.domain.processor.analysis.ConnectivityAnalysisProcessor
import com.friady.sailens.domain.processor.analysis.ConnectivityChecker
import com.friady.sailens.domain.processor.analysis.CrossValidator
import com.friady.sailens.domain.processor.analysis.GroundTypeDetector
import com.friady.sailens.domain.processor.analysis.RoadSafetyAnalyzer
import com.friady.sailens.domain.processor.analysis.SceneClassifier
import com.friady.sailens.domain.processor.decision.CooldownManager
import com.friady.sailens.domain.processor.decision.EventConflictResolver
import com.friady.sailens.domain.processor.decision.EventGenerator
import com.friady.sailens.domain.processor.decision.EventMerger
import com.friady.sailens.domain.processor.perception.ObstacleExtractor
import com.friady.sailens.domain.processor.perception.ObstacleTracker
import com.friady.sailens.domain.processor.perception.SegmentationAnalysisProcessor
import com.friady.sailens.domain.processor.perception.SegmentationAnalyzer
import com.friady.sailens.domain.usecase.decision.DecideEventsUseCase
import com.friady.sailens.domain.usecase.perception.AnalyzeSceneUseCase
import com.friady.sailens.domain.usecase.perception.ProcessFrameUseCase
import com.friady.sailens.domain.usecase.scene.StartSceneAnalysisUseCase
import com.friady.sailens.domain.usecase.scene.StopSceneAnalysisUseCase
import com.friady.sailens.domain.usecase.trace.BuildTraceReplayReportUseCase
import com.friady.sailens.domain.usecase.trace.EvaluateTraceReplayBudgetUseCase
import com.friady.sailens.domain.usecase.trace.ListTraceSessionsUseCase
import com.friady.sailens.domain.usecase.trace.LoadLatestTraceReplayReportUseCase
import com.friady.sailens.domain.usecase.trace.LoadTraceReplayReportUseCase
import com.friady.sailens.presentation.scene.SceneOverlayConfig
import org.koin.dsl.module

val domainBindingsModule = module {
    single { SailensRuntimeProfile.balanced() }
    single<CameraRuntimeConfig> { get<SailensRuntimeProfile>().camera }
    single<YOLO26SemModelConfig> { get<SailensRuntimeProfile>().semanticModel }
    single<YOLO26SegModelConfig> { get<SailensRuntimeProfile>().instanceModel }
    single<PerceptionConfig> { get<SailensRuntimeProfile>().perception }
    single<AnalysisConfig> { get<SailensRuntimeProfile>().analysis }
    single<PipelinePerformanceBudget> { get<SailensRuntimeProfile>().pipelineBudget }
    single<SceneOverlayConfig> { get<SailensRuntimeProfile>().sceneOverlay }

    single<SegmentationAnalysisProcessor> { SegmentationAnalyzer(config = get(), classMapper = get()) }
    single<ConnectivityAnalysisProcessor> { ConnectivityChecker(config = get(), statsExtractor = get()) }
    single { ObstacleExtractor(config = get(), classMapper = get()) }
    single { ObstacleTracker(config = get()) }
    single { RoadSafetyAnalyzer(config = get(), classMapper = get()) }
    single { GroundTypeDetector(config = get()) }
    single { SceneClassifier(config = get()) }
    single { CrossValidator(config = get()) }
    single { EventGenerator(config = get()) }
    single { EventConflictResolver() }
    single { EventMerger() }
    single { CooldownManager() }

    factory {
        ProcessFrameUseCase(
            perceptionConfig = get(),
            perceptionRepository = get(),
            instanceProvider = get(),
            depthRepository = get(),
            segmentationAnalyzer = get(),
            obstacleExtractor = get(),
            obstacleTracker = get(),
        )
    }
    factory {
        AnalyzeSceneUseCase(
            connectivityChecker = get(),
            roadSafetyAnalyzer = get(),
            groundTypeDetector = get(),
            sceneClassifier = get(),
            crossValidator = get(),
        )
    }
    factory {
        DecideEventsUseCase(
            eventGenerator = get(),
            conflictResolver = get(),
            eventMerger = get(),
            cooldownManager = get(),
        )
    }
    factory {
        StartSceneAnalysisUseCase(
            perceptionConfig = get(),
            perceptionRepository = get(),
            instanceProvider = get(),
            processFrameUseCase = get(),
            analyzeSceneUseCase = get(),
            decideEventsUseCase = get(),
            logService = get(),
            traceService = get(),
            pipelineBudget = get(),
        )
    }
    factory {
        StopSceneAnalysisUseCase(
            perceptionRepository = get(),
            instanceProvider = get(),
            segmentationAnalyzer = get(),
            obstacleTracker = get(),
            connectivityChecker = get(),
            roadSafetyAnalyzer = get(),
            groundTypeDetector = get(),
            sceneClassifier = get(),
            eventGenerator = get(),
            cooldownManager = get(),
            logService = get(),
        )
    }
    factory { BuildTraceReplayReportUseCase() }
    factory { EvaluateTraceReplayBudgetUseCase(get()) }
    factory { ListTraceSessionsUseCase(get()) }
    factory { LoadTraceReplayReportUseCase(get(), get()) }
    factory { LoadLatestTraceReplayReportUseCase(get(), get()) }
}
