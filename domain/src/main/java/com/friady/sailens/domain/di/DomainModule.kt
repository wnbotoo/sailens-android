package com.friady.sailens.domain.di

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.config.PerceptionConfig
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
import com.friady.sailens.domain.processor.perception.SegmentationAnalyzer
import com.friady.sailens.domain.usecase.decision.DecideEventsUseCase
import com.friady.sailens.domain.usecase.perception.AnalyzeSceneUseCase
import com.friady.sailens.domain.usecase.perception.ProcessFrameUseCase
import com.friady.sailens.domain.usecase.scene.StartSceneAnalysisUseCase
import com.friady.sailens.domain.usecase.scene.StopSceneAnalysisUseCase
import org.koin.dsl.module

val domainModule = module {

    // 配置
    single { PerceptionConfig() }
    single { AnalysisConfig() }

    // 处理器（需要 ClassMapper 的都注入）
    single { SegmentationAnalyzer(config = get(), classMapper = get()) }
    single { ObstacleExtractor(config = get(), classMapper = get()) }
    single { ObstacleTracker(config = get()) }
    single { ConnectivityChecker(config = get()) }
    single { RoadSafetyAnalyzer(config = get(), classMapper = get()) }
    single { GroundTypeDetector(config = get()) }
    single { SceneClassifier(config = get()) }
    single { CrossValidator(config = get()) }
    single { EventGenerator() }
    single { EventConflictResolver() }
    single { EventMerger() }
    single { CooldownManager() }

    // 用例
    factory {
        ProcessFrameUseCase(
            get(),
            get(),
            null,
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
            null,
            get(),
            get(),
            get(),
            get()
        )
    }
    factory {
        StopSceneAnalysisUseCase(
            null,
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
}