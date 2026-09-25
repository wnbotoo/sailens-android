package com.sailens.guidance.di

import android.content.Context
import com.sailens.core.log.LogService
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.config.TraceRuntimeConfig
import com.sailens.guidance.depth.DefaultDepthRepository
import com.sailens.guidance.depth.ImagePositionDepthEstimator
import com.sailens.guidance.kernel.NativeConnectivityStatsExtractor
import com.sailens.guidance.kernel.NavigationScorePostprocessor
import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.guidance.model.common.SemanticProviderType
import com.sailens.guidance.perception.DetectorObstacleProvider
import com.sailens.guidance.perception.DisabledObstacleProvider
import com.sailens.guidance.perception.SemanticPerceptionRepository
import com.sailens.guidance.processor.analysis.ConnectivityStatsExtractor
import com.sailens.guidance.repository.DepthRepository
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.repository.ObstacleProvider
import com.sailens.guidance.repository.PerceptionRepository
import com.sailens.guidance.semantics.DefaultNavigationSemanticsProvider
import com.sailens.guidance.semantics.NavigationSemantics
import com.sailens.guidance.semantics.NavigationSemanticsProvider
import com.sailens.guidance.sensors.DefaultDeviceSensorRepository
import com.sailens.guidance.sensors.DeviceMotionDataSource
import com.sailens.guidance.sensors.DeviceRotationDataSource
import com.sailens.guidance.service.TraceReplayService
import com.sailens.guidance.service.TraceService
import com.sailens.guidance.service.TraceServiceDecorator
import com.sailens.guidance.trace.FileTraceReplayService
import com.sailens.guidance.trace.FileTraceService
import com.sailens.guidance.trace.NoOpTraceService
import com.sailens.runtime.CatalogModelSourceResolver
import com.sailens.runtime.InputPreprocessCache
import com.sailens.runtime.ModelSourceResolver
import com.sailens.vision.detection.DetectionModelConfig
import com.sailens.vision.semantic.SemanticModelConfig
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Guidance's own object graph: the navigation semantics, the perception sources it drives and the
 * per-session services around them.
 *
 * The host app composes this module; it does not reach inside it. Configs are supplied by the host
 * (profile bindings), and the Describe/VLM side is wired separately — Guidance and Describe never
 * see each other (architecture.md §4.2 rule 2).
 */
val guidanceModule = module {
    single<NavigationSemanticsProvider> {
        DefaultNavigationSemanticsProvider()
    }

    single<NavigationSemantics> {
        get<NavigationSemanticsProvider>().semanticSegmentationSemantics()
    }

    single<ModelSourceResolver> { CatalogModelSourceResolver }

    single {
        NavigationScorePostprocessor(
            config = get(),
            navigationSemantics = get(),
            logService = get(),
        )
    }

    single<ConnectivityStatsExtractor> {
        NativeConnectivityStatsExtractor(
            config = get(),
            logService = get(),
        )
    }

    // Same-frame cross-provider cache. The current sem/det path starts both models concurrently, so
    // cache hits are opportunistic rather than guaranteed, but when one preprocessor finishes first
    // the other can reuse the identical 640x640 YUV->tensor conversion instead of doing it again.
    // One shared instance, not one per runner (architecture.md §6.8).
    single { InputPreprocessCache() }

    // Perception sources
    single<PerceptionRepository> {
        createPerceptionRepository(
            providerType = get<PerceptionConfig>().semanticProviderType,
            context = androidContext(),
            modelConfig = get(),
            modelSourceResolver = get(),
            scorePostprocessor = get<NavigationScorePostprocessor>(),
            preprocessCache = get(),
            logService = get(),
        )
    }
    single<ObstacleProvider>(named("realtimeObstacleProvider")) {
        createObstacleProvider(
            providerType = get<PerceptionConfig>().realtimeObstacleProviderType,
            context = androidContext(),
            perceptionConfig = get(),
            modelConfig = get(),
            modelSourceResolver = get(),
            preprocessCache = get(),
            logService = get(),
        )
    }
    single { ImagePositionDepthEstimator() }
    single { DeviceRotationDataSource(context = androidContext()) }
    single { DeviceMotionDataSource(context = androidContext()) }

    single<DepthRepository> { DefaultDepthRepository(get(), null) }
    single<DeviceSensorRepository> { DefaultDeviceSensorRepository(get(), get()) }

    // Trace / replay
    single<TraceService> {
        val traceRuntimeConfig = get<TraceRuntimeConfig>()
        val base = if (traceRuntimeConfig.enabled) {
            FileTraceService(
                context = androidContext(),
                logService = get(),
                traceRuntimeConfig = traceRuntimeConfig,
            )
        } else {
            NoOpTraceService
        }
        // Debug builds bind a decorator that adds field capture; release binds none.
        getOrNull<TraceServiceDecorator>()?.decorate(base) ?: base
    }
    single<TraceReplayService> { FileTraceReplayService(androidContext()) }
}

// Provider selection lives in these factory functions, not in the module body, so the `when`
// switches stay out of the Koin definitions (DI wires; it does not decide) and are unit-testable.

private fun createPerceptionRepository(
    providerType: SemanticProviderType,
    context: Context,
    modelConfig: SemanticModelConfig,
    modelSourceResolver: ModelSourceResolver,
    scorePostprocessor: NavigationScorePostprocessor,
    preprocessCache: InputPreprocessCache,
    logService: LogService,
): PerceptionRepository = when (providerType) {
    SemanticProviderType.SEGMENTATION_MODEL -> SemanticPerceptionRepository(
        context = context,
        scorePostprocessor = scorePostprocessor,
        modelConfig = modelConfig,
        modelSourceResolver = modelSourceResolver,
        preprocessCache = preprocessCache,
        logService = logService,
    )
}

private fun createObstacleProvider(
    providerType: ObstacleProviderType,
    context: Context,
    perceptionConfig: PerceptionConfig,
    modelConfig: DetectionModelConfig,
    modelSourceResolver: ModelSourceResolver,
    preprocessCache: InputPreprocessCache,
    logService: LogService,
): ObstacleProvider = when (providerType) {
    ObstacleProviderType.NONE -> DisabledObstacleProvider()
    ObstacleProviderType.DETECTION_MODEL -> DetectorObstacleProvider(
        context = context,
        perceptionConfig = perceptionConfig,
        modelConfig = modelConfig,
        modelSourceResolver = modelSourceResolver,
        preprocessCache = preprocessCache,
        logService = logService,
    )
}
