package com.friady.sailens.app

import com.friady.sailens.camera.CameraRuntimeConfig
import com.friady.sailens.data.source.ml.instance.YOLO26SegModelConfig
import com.friady.sailens.data.source.ml.semantic.YOLO26SemModelConfig
import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.config.PerceptionConfig
import com.friady.sailens.domain.model.common.InferenceStrategy
import com.friady.sailens.domain.model.common.PerceptionMode
import com.friady.sailens.domain.model.common.SemanticProviderType
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
import org.koin.dsl.module

val domainBindingsModule = module {
    // 640x360 minimizes camera bandwidth; 960x540 is a balanced default for the 640 square models.
    // The selected TFLite asset still owns the actual model tensor shape.
    single {
        CameraRuntimeConfig(
            previewWidth = 1280,
            previewHeight = 720,
            analysisWidth = 960,
            analysisHeight = 540,
        )
    }
    single {
        YOLO26SemModelConfig(
            assetPath = "yolo26n-sem-640_int8.tflite",
        )
    }
    single {
        YOLO26SegModelConfig(
            assetPath = "yolo26n-seg-640_int8.tflite",
            enableMaskReconstruction = false,
        )
    }
    single {
        PerceptionConfig(
            mode = PerceptionMode.COMBINED,
            semanticProviderType = SemanticProviderType.YOLO26_SEM,
            inferenceStrategy = InferenceStrategy.ALTERNATING,
            enableSemanticFrameSkipping = false,
            semanticFrameInterval = 2,
        )
    }
    single { AnalysisConfig() }

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
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
    factory {
        AnalyzeSceneUseCase(
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
    factory {
        DecideEventsUseCase(
            get(),
            get(),
            get(),
            get()
        )
    }
    factory {
        StartSceneAnalysisUseCase(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
    factory {
        StopSceneAnalysisUseCase(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
    factory { BuildTraceReplayReportUseCase() }
    factory { EvaluateTraceReplayBudgetUseCase() }
    factory { ListTraceSessionsUseCase(get()) }
    factory { LoadTraceReplayReportUseCase(get(), get()) }
    factory { LoadLatestTraceReplayReportUseCase(get(), get()) }
}
