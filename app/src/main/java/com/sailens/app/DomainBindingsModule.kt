package com.sailens.app

import com.sailens.guidance.processor.analysis.ConnectivityAnalysisProcessor
import com.sailens.guidance.processor.analysis.ConnectivityChecker
import com.sailens.guidance.processor.analysis.CrossValidator
import com.sailens.guidance.processor.analysis.GroundTypeDetector
import com.sailens.guidance.processor.analysis.ObstacleOcclusionAnalyzer
import com.sailens.guidance.processor.analysis.RoadSafetyAnalyzer
import com.sailens.guidance.processor.analysis.FrameQualityAnalyzer
import com.sailens.guidance.processor.analysis.SceneClassifier
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.processor.perception.ObstacleExtractor
import com.sailens.guidance.processor.perception.ObstacleTracker
import com.sailens.guidance.processor.perception.PerceptionProfileManager
import com.sailens.guidance.processor.perception.SegmentationAnalysisProcessor
import com.sailens.guidance.processor.perception.SegmentationAnalyzer
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import com.sailens.guidance.usecase.perception.AnalyzeSceneUseCase
import com.sailens.guidance.usecase.perception.ProcessFrameUseCase
import com.sailens.guidance.usecase.scene.DescribeSceneUseCase
import com.sailens.guidance.usecase.scene.StartSceneAnalysisUseCase
import com.sailens.guidance.usecase.scene.StopSceneAnalysisUseCase
import com.sailens.guidance.usecase.trace.BuildTraceReplayReportUseCase
import com.sailens.guidance.usecase.trace.EvaluateTraceReplayBudgetUseCase
import com.sailens.guidance.usecase.trace.ListTraceSessionsUseCase
import com.sailens.guidance.usecase.trace.LoadLatestTraceReplayReportUseCase
import com.sailens.guidance.usecase.trace.LoadTraceReplayReportUseCase
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Wires the domain layer at the app composition root: stateless processors as singles and use cases
 * as factories. Domain classes carry no DI annotations themselves, so their graph is assembled here.
 *
 * Configs come from [profileBindingsModule]; the obstacle provider is bound in `dataModule` under
 * the `realtimeObstacleProvider` qualifier.
 */
val domainBindingsModule = module {
    // Processors — stateless, shared singletons.
    single<SegmentationAnalysisProcessor> { SegmentationAnalyzer(config = get(), navigationSemantics = get()) }
    single<ConnectivityAnalysisProcessor> { ConnectivityChecker(config = get(), statsExtractor = get()) }
    single { ObstacleExtractor(config = get(), navigationSemantics = get()) }
    single { ObstacleTracker(config = get()) }
    // 设置页选择的挡位经由 manager 在下一次导航会话开始时生效
    single { PerceptionProfileManager(initialConfig = get()) }
    single { RoadSafetyAnalyzer(config = get(), navigationSemantics = get()) }
    single { ObstacleOcclusionAnalyzer(config = get()) }
    single { GroundTypeDetector(config = get()) }
    single { SceneClassifier(config = get()) }
    single { CrossValidator(config = get()) }
    // 有跨帧去抖状态，必须和其他 stabilizer 一样由 Start/Stop 对称 reset。
    single { FrameQualityAnalyzer() }
    single { EventGenerator(config = get()) }
    single { EventConflictResolver() }
    single { EventMerger() }
    single { CooldownManager() }

    // Use cases — new instance per resolution.
    // ProcessFrameUseCase is the exception: it holds per-session frame state (cached semantic
    // analysis, frame counters) that must be reset symmetrically by both Start and Stop, so it is a
    // shared single both reference. See StopSceneAnalysisUseCase.resetProcessors().
    single {
        ProcessFrameUseCase(
            profileManager = get(),
            perceptionRepository = get(),
            realtimeObstacleProvider = get(named("realtimeObstacleProvider")),
            depthRepository = get(),
            segmentationAnalyzer = get(),
            obstacleExtractor = get(),
            obstacleTracker = get(),
            // 调度间隔与轨迹 TTL 必须用单调时钟：墙钟（currentTimeMillis）会被
            // NTP 校时/手动改时间跳变，回跳会让过期轨迹迟迟不清、持续播报
            clock = { android.os.SystemClock.elapsedRealtime() },
        )
    }
    factory {
        AnalyzeSceneUseCase(
            connectivityChecker = get(),
            roadSafetyAnalyzer = get(),
            groundTypeDetector = get(),
            sceneClassifier = get(),
            crossValidator = get(),
            obstacleOcclusionAnalyzer = get(),
        )
    }
    factory {
        DecideEventsUseCase(
            eventGenerator = get(),
            conflictResolver = get(),
            eventMerger = get(),
            cooldownManager = get(),
            deviceSensorRepository = get(),
        )
    }
    factory {
        StartSceneAnalysisUseCase(
            profileManager = get(),
            perceptionRepository = get(),
            realtimeObstacleProvider = get(named("realtimeObstacleProvider")),
            processFrameUseCase = get(),
            analyzeSceneUseCase = get(),
            decideEventsUseCase = get(),
            frameQualityAnalyzer = get(),
            logService = get(),
            traceService = get(),
            traceRuntimeConfig = get(),
            pipelineBudget = get(),
        )
    }
    factory {
        StopSceneAnalysisUseCase(
            perceptionRepository = get(),
            realtimeObstacleProvider = get(named("realtimeObstacleProvider")),
            processFrameUseCase = get(),
            segmentationAnalyzer = get(),
            obstacleTracker = get(),
            connectivityChecker = get(),
            roadSafetyAnalyzer = get(),
            groundTypeDetector = get(),
            sceneClassifier = get(),
            frameQualityAnalyzer = get(),
            cooldownManager = get(),
            logService = get(),
        )
    }
    factory { DescribeSceneUseCase(sceneDescriber = get(), logService = get()) }
    factory { BuildTraceReplayReportUseCase() }
    factory { EvaluateTraceReplayBudgetUseCase(get()) }
    factory { ListTraceSessionsUseCase(get()) }
    factory { LoadTraceReplayReportUseCase(get(), get()) }
    factory { LoadLatestTraceReplayReportUseCase(get(), get()) }
}
