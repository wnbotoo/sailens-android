package com.sailens.app

import com.sailens.camera.CameraRuntimeConfig
import com.sailens.runtime.ModelAcceleratorBackend
import com.sailens.vision.detection.DetectionModelConfig
import com.sailens.vision.semantic.SemanticModelConfig
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.config.PipelinePerformanceBudget
import com.sailens.guidance.config.TraceRuntimeConfig
import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.shell.guidance.overlay.SceneOverlayConfig

/**
 * Single app-level "preset" that bundles every runtime-tuning knob — camera source resolution, the
 * per-model accelerator targets, the perception/analysis configs, and pipeline cadence — so they are
 * changed together as one coherent set instead of drifting apart across the codebase.
 *
 * A profile only carries *which* model runs on *which* accelerator (see [semanticModel] etc.); the
 * physical `.tflite` for each (type, accelerator) pairing is resolved in the data layer by
 * `ModelSourceResolver` / `ModelCatalog`, so no file name appears here.
 *
 * There is one profile, [standard]: every model, the VLM included, targets the GPU. An `ultra`
 * tier that reserved the NPU for the VLM used to sit beside it, but nothing ever selected it — no
 * VLM runtime this app can ship gets the NPU on the target SoCs — so it was removed rather than
 * kept as a promise. A second profile comes back when a second hardware path actually exists.
 *
 * Orthogonal to the profile, [PerceptionProfile] decides *which* models run per frame (see
 * `PerceptionConfig.forProfile`): BASIC = sem only, DEFAULT = sem + det. The shipped default is
 * DEFAULT (seeded from `PerceptionSettingsStore`).
 */
data class SailensRuntimeProfile(
    val name: String,
    /**
     * Descriptive label of the detected SoC/device (from [DeviceHardwareProfileProvider.detect]).
     * A tag for logs/traces and the Settings screen.
     */
    val targetHardwareProfile: String,
    val camera: CameraRuntimeConfig,
    val semanticModel: SemanticModelConfig,
    val realtimeObstacleModel: DetectionModelConfig,
    val vlmModelBackend: ModelAcceleratorBackend,
    val perception: PerceptionConfig,
    val analysis: AnalysisConfig,
    val pipelineBudget: PipelinePerformanceBudget,
    val trace: TraceRuntimeConfig,
    val sceneOverlay: SceneOverlayConfig,
) {
    companion object {
        fun standard(
            targetHardwareProfile: String = UNKNOWN_HARDWARE,
            enableDiagnostics: Boolean = true,
            perceptionProfile: PerceptionProfile = PerceptionProfile.DEFAULT,
        ): SailensRuntimeProfile {
            val name = STANDARD
            val camera = CameraRuntimeConfig(
                previewWidth = 1280,
                previewHeight = 720,
                analysisWidth = 960,
                analysisHeight = 540,
            )
            return SailensRuntimeProfile(
                name = name,
                targetHardwareProfile = targetHardwareProfile,
                camera = camera,
                semanticModel = SemanticModelConfig(
                    acceleratorBackend = ModelAcceleratorBackend.GPU,
                ),
                realtimeObstacleModel = DetectionModelConfig(
                    acceleratorBackend = ModelAcceleratorBackend.GPU,
                ),
                vlmModelBackend = ModelAcceleratorBackend.GPU,
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

        private const val STANDARD = "standard"
        private const val UNKNOWN_HARDWARE = "unknown_hardware"
    }
}
