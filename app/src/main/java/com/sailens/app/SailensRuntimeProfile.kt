package com.sailens.app

import com.sailens.camera.CameraRuntimeConfig
import com.sailens.data.source.ml.ModelAcceleratorBackend
import com.sailens.data.source.ml.obstacle.ObstacleModelConfig
import com.sailens.data.source.ml.semantic.SemanticModelConfig
import com.sailens.domain.config.AnalysisConfig
import com.sailens.domain.config.PerceptionConfig
import com.sailens.domain.config.PipelinePerformanceBudget
import com.sailens.domain.config.TraceRuntimeConfig
import com.sailens.domain.model.common.ObstacleProviderType
import com.sailens.domain.model.common.PerceptionProfile
import com.sailens.shell.guidance.overlay.SceneOverlayConfig

enum class SailensPerformanceTier(val profileName: String) {
    STANDARD("standard"),
    ULTRA("ultra"),
}

/**
 * Single app-level "preset" that bundles every runtime-tuning knob — camera source resolution, the
 * per-model accelerator targets, the perception/analysis configs, and pipeline cadence — so they are
 * changed together as one coherent set instead of drifting apart across the codebase.
 *
 * A profile only carries *which* model runs on *which* accelerator (see [semanticModel] etc.); the
 * physical `.tflite` for each (type, accelerator) pairing is resolved in the data layer by
 * `ModelSourceResolver` / `ModelCatalog`, so no file name appears here.
 *
 * Tier policy:
 * - [standard]: all bundled vision models run on GPU. This is the broad release default.
 * - [ultra]: reserves NPU for a future VLM path, so the realtime vision models stay on GPU.
 *
 * Orthogonal to the tier, [PerceptionProfile] decides *which* models run per frame (see
 * `PerceptionConfig.forProfile`): BASIC = sem only, DEFAULT = sem + det. The shipped default is
 * DEFAULT (seeded from `PerceptionSettingsStore`).
 */
data class SailensRuntimeProfile(
    val name: String,
    val tier: SailensPerformanceTier,
    /**
     * Descriptive label of the detected SoC/device (from [DeviceHardwareProfileProvider.detect]).
     * A tag for logs/traces and the Settings screen.
     */
    val targetHardwareProfile: String,
    val camera: CameraRuntimeConfig,
    val semanticModel: SemanticModelConfig,
    val realtimeObstacleModel: ObstacleModelConfig,
    val vlmModelBackend: ModelAcceleratorBackend,
    val perception: PerceptionConfig,
    val analysis: AnalysisConfig,
    val pipelineBudget: PipelinePerformanceBudget,
    val trace: TraceRuntimeConfig,
    val sceneOverlay: SceneOverlayConfig,
) {
    companion object {
        fun select(
            targetHardwareProfile: String = UNKNOWN_HARDWARE,
            vlmNpuAvailable: Boolean = false,
            enableDiagnostics: Boolean = true,
            perceptionProfile: PerceptionProfile = PerceptionProfile.DEFAULT,
        ): SailensRuntimeProfile {
            return forTier(
                tier = selectTier(targetHardwareProfile, vlmNpuAvailable),
                targetHardwareProfile = targetHardwareProfile,
                enableDiagnostics = enableDiagnostics,
                perceptionProfile = perceptionProfile,
            )
        }

        fun standard(
            targetHardwareProfile: String = UNKNOWN_HARDWARE,
            enableDiagnostics: Boolean = true,
            perceptionProfile: PerceptionProfile = PerceptionProfile.DEFAULT,
        ): SailensRuntimeProfile =
            build(
                tier = SailensPerformanceTier.STANDARD,
                targetHardwareProfile = targetHardwareProfile,
                semanticBackend = ModelAcceleratorBackend.GPU,
                realtimeObstacleBackend = ModelAcceleratorBackend.GPU,
                vlmBackend = ModelAcceleratorBackend.GPU,
                enableDiagnostics = enableDiagnostics,
                perceptionProfile = perceptionProfile,
            )

        fun ultra(
            targetHardwareProfile: String = UNKNOWN_HARDWARE,
            enableDiagnostics: Boolean = true,
            perceptionProfile: PerceptionProfile = PerceptionProfile.DEFAULT,
        ): SailensRuntimeProfile =
            build(
                tier = SailensPerformanceTier.ULTRA,
                targetHardwareProfile = targetHardwareProfile,
                semanticBackend = ModelAcceleratorBackend.GPU,
                realtimeObstacleBackend = ModelAcceleratorBackend.GPU,
                vlmBackend = ModelAcceleratorBackend.NPU,
                enableDiagnostics = enableDiagnostics,
                perceptionProfile = perceptionProfile,
            )

        internal fun selectTier(
            targetHardwareProfile: String,
            vlmNpuAvailable: Boolean = false,
        ): SailensPerformanceTier {
            return if (vlmNpuAvailable && targetHardwareProfile != UNKNOWN_HARDWARE) {
                SailensPerformanceTier.ULTRA
            } else {
                SailensPerformanceTier.STANDARD
            }
        }

        private fun forTier(
            tier: SailensPerformanceTier,
            targetHardwareProfile: String,
            enableDiagnostics: Boolean,
            perceptionProfile: PerceptionProfile,
        ): SailensRuntimeProfile = when (tier) {
            SailensPerformanceTier.STANDARD ->
                standard(targetHardwareProfile, enableDiagnostics, perceptionProfile)
            SailensPerformanceTier.ULTRA ->
                ultra(targetHardwareProfile, enableDiagnostics, perceptionProfile)
        }

        private fun build(
            tier: SailensPerformanceTier,
            targetHardwareProfile: String,
            semanticBackend: ModelAcceleratorBackend,
            realtimeObstacleBackend: ModelAcceleratorBackend,
            vlmBackend: ModelAcceleratorBackend,
            enableDiagnostics: Boolean,
            perceptionProfile: PerceptionProfile,
        ): SailensRuntimeProfile {
            val name = tier.profileName
            val camera = CameraRuntimeConfig(
                previewWidth = 1280,
                previewHeight = 720,
                analysisWidth = 960,
                analysisHeight = 540,
            )
            return SailensRuntimeProfile(
                name = name,
                tier = tier,
                targetHardwareProfile = targetHardwareProfile,
                camera = camera,
                semanticModel = SemanticModelConfig(
                    acceleratorBackend = semanticBackend,
                ),
                realtimeObstacleModel = ObstacleModelConfig(
                    acceleratorBackend = realtimeObstacleBackend,
                ),
                vlmModelBackend = vlmBackend,
                perception = PerceptionConfig.forProfile(
                    profile = perceptionProfile,
                    runtimeProfileName = name,
                    targetHardwareProfile = targetHardwareProfile,
                    realtimeObstacleProviderType = ObstacleProviderType.DETECTION_MODEL,
                ),
                analysis = AnalysisConfig(),
                pipelineBudget = PipelinePerformanceBudget(),
                trace = TraceRuntimeConfig(enabled = enableDiagnostics),
                sceneOverlay = SceneOverlayConfig.forDiagnostics(enableDiagnostics),
            )
        }

        private const val UNKNOWN_HARDWARE = "unknown_hardware"
    }
}
