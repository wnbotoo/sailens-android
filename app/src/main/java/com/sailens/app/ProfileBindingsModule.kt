package com.sailens.app

import com.sailens.BuildConfig
import com.sailens.camera.CameraRuntimeConfig
import com.sailens.data.source.ml.obstacle.ObstacleModelConfig
import com.sailens.data.source.ml.semantic.SemanticModelConfig
import com.sailens.data.source.ml.vlm.VlmModelConfig
import com.sailens.domain.config.AnalysisConfig
import com.sailens.domain.config.PerceptionConfig
import com.sailens.domain.config.PipelinePerformanceBudget
import com.sailens.domain.config.TraceRuntimeConfig
import com.sailens.presentation.scene.SceneOverlayConfig
import com.sailens.presentation.settings.PerceptionSettingsStore
import org.koin.dsl.module

/**
 * Binds the single active [SailensRuntimeProfile] and fans it out into the individual config types
 * that the data / domain / camera / presentation layers inject.
 *
 * Those layers depend only on their own config type (e.g. [PerceptionConfig], [SemanticModelConfig]),
 * never on the app-level profile aggregate, so this fan-out is the boundary translation between "one
 * cohesive preset" and "each consumer gets its slice".
 */
val profileBindingsModule = module {
    single {
        SailensRuntimeProfile.select(
            targetHardwareProfile = DeviceHardwareProfileProvider.detect(),
            enableDiagnostics = BuildConfig.SHOW_DIAGNOSTICS,
            // 设置页持久化的挡位选择；运行中的切换走 PerceptionProfileManager
            perceptionProfile = get<PerceptionSettingsStore>().profile.value,
        )
    }
    single<CameraRuntimeConfig> { get<SailensRuntimeProfile>().camera }
    single<SemanticModelConfig> { get<SailensRuntimeProfile>().semanticModel }
    single<ObstacleModelConfig> { get<SailensRuntimeProfile>().realtimeObstacleModel }
    // The VLM's backend is the profile's call (the ultra tier reserves NPU for it); everything else
    // about it — prompt, decode budget — is the model config's own default.
    single {
        VlmModelConfig(
            modelPath = VlmModelConfig.DEFAULT_MODEL_ASSET,
            acceleratorBackend = get<SailensRuntimeProfile>().vlmModelBackend,
        )
    }
    single<PerceptionConfig> { get<SailensRuntimeProfile>().perception }
    single<AnalysisConfig> { get<SailensRuntimeProfile>().analysis }
    single<PipelinePerformanceBudget> { get<SailensRuntimeProfile>().pipelineBudget }
    single<TraceRuntimeConfig> { get<SailensRuntimeProfile>().trace }
    single<SceneOverlayConfig> { get<SailensRuntimeProfile>().sceneOverlay }
}
