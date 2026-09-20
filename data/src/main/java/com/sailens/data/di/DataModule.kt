package com.sailens.data.di

import android.content.Context
import com.sailens.data.repository.DefaultDepthRepository
import com.sailens.data.repository.DefaultDeviceSensorRepository
import com.sailens.data.repository.MLPerceptionRepository
import com.sailens.data.service.FileLogService
import com.sailens.data.service.FileTraceReplayService
import com.sailens.data.service.FileTraceService
import com.sailens.data.service.NoOpTraceService
import com.sailens.data.source.depth.ImagePositionDepthEstimator
import com.sailens.data.source.device.DeviceMotionDataSource
import com.sailens.data.source.device.DeviceRotationDataSource
import com.sailens.domain.semantics.DefaultNavigationSemanticsProvider
import com.sailens.runtime.CatalogModelSourceResolver
import com.sailens.runtime.InputPreprocessCache
import com.sailens.runtime.ModelSourceResolver
import com.sailens.data.source.ml.analysis.NativeConnectivityStatsExtractor
import com.sailens.data.source.ml.obstacle.DisabledObstacleProvider
import com.sailens.vision.detection.DetectionModelConfig
import com.sailens.data.source.ml.obstacle.LiteRtObstacleProvider
import com.sailens.data.source.ml.semantic.NativeSemanticScorePostprocessor
import com.sailens.data.source.ml.semantic.SegmentationModel
import com.sailens.vision.semantic.SemanticModelConfig
import com.sailens.data.source.ml.semantic.LiteRtSemanticSegmentationModel
import com.sailens.data.source.ml.vlm.LiteRtVlmEngine
import com.sailens.domain.config.PerceptionConfig
import com.sailens.domain.config.TraceRuntimeConfig
import com.sailens.domain.model.common.ObstacleProviderType
import com.sailens.domain.model.common.SemanticProviderType
import com.sailens.domain.semantics.NavigationSemantics
import com.sailens.domain.semantics.NavigationSemanticsProvider
import com.sailens.domain.processor.analysis.ConnectivityStatsExtractor
import com.sailens.domain.repository.DepthRepository
import com.sailens.domain.repository.DeviceSensorRepository
import com.sailens.domain.repository.ObstacleProvider
import com.sailens.domain.repository.PerceptionRepository
import com.sailens.domain.repository.SceneDescriber
import com.sailens.core.log.LogService
import com.sailens.domain.service.TraceReplayService
import com.sailens.domain.service.TraceService
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

val dataModule = module {
    single<NavigationSemanticsProvider> {
        DefaultNavigationSemanticsProvider()
    }

    single<NavigationSemantics> {
        get<NavigationSemanticsProvider>().semanticSegmentationSemantics()
    }

    single<ModelSourceResolver> { CatalogModelSourceResolver }

    // On-demand scene description. A `single` because it owns a loaded model, but it stays unloaded
    // until the first request (see DescribeSceneUseCase) — and with the default
    // UnavailableVlmRuntimeFactory it reports isAvailable == false and the UI hides the action, so
    // constructing it here costs nothing until a real runtime is injected.
    single<SceneDescriber> {
        LiteRtVlmEngine(
            context = androidContext(),
            config = get(),
            logService = get(),
        )
    }

    single {
        NativeSemanticScorePostprocessor(
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
    single { InputPreprocessCache() }

    // Data source
    single<SegmentationModel> {
        createSegmentationModel(
            providerType = get<PerceptionConfig>().semanticProviderType,
            context = androidContext(),
            modelConfig = get(),
            modelSourceResolver = get(),
            nativeScorePostprocessor = get<NativeSemanticScorePostprocessor>(),
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

    // Repository
    single<PerceptionRepository> { MLPerceptionRepository(get()) }
    single<DepthRepository> { DefaultDepthRepository(get(), null) }
    single<DeviceSensorRepository> { DefaultDeviceSensorRepository(get(), get()) }


    // service
    single<LogService> { FileLogService(androidContext()) }
    single<TraceService> {
        val traceRuntimeConfig = get<TraceRuntimeConfig>()
        if (traceRuntimeConfig.enabled) {
            FileTraceService(
                context = androidContext(),
                logService = get(),
                traceRuntimeConfig = traceRuntimeConfig,
            )
        } else {
            NoOpTraceService
        }
    }
    single<TraceReplayService> { FileTraceReplayService(androidContext()) }
}

// Provider selection lives in these factory functions, not in the module body, so the `when`
// switches stay out of the Koin definitions (DI wires; it does not decide) and are unit-testable.

private fun createSegmentationModel(
    providerType: SemanticProviderType,
    context: Context,
    modelConfig: SemanticModelConfig,
    modelSourceResolver: ModelSourceResolver,
    nativeScorePostprocessor: NativeSemanticScorePostprocessor,
    preprocessCache: InputPreprocessCache,
    logService: LogService,
): SegmentationModel = when (providerType) {
    SemanticProviderType.SEGMENTATION_MODEL -> LiteRtSemanticSegmentationModel(
        context = context,
        modelConfig = modelConfig,
        modelSourceResolver = modelSourceResolver,
        nativeScorePostprocessor = nativeScorePostprocessor,
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
    ObstacleProviderType.DETECTION_MODEL -> LiteRtObstacleProvider(
        context = context,
        perceptionConfig = perceptionConfig,
        modelConfig = modelConfig,
        modelSourceResolver = modelSourceResolver,
        preprocessCache = preprocessCache,
        logService = logService,
    )
}
