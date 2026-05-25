package com.friady.sailens.app

import com.friady.sailens.camera.CameraRuntimeConfig
import com.friady.sailens.data.source.ml.instance.YOLO26SegModelConfig
import com.friady.sailens.data.source.ml.semantic.YOLO26SemModelConfig
import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.config.PerceptionConfig
import com.friady.sailens.domain.config.PipelinePerformanceBudget
import com.friady.sailens.domain.model.common.InferenceStrategy
import com.friady.sailens.domain.model.common.InstanceProviderType
import com.friady.sailens.domain.model.common.PerceptionMode
import com.friady.sailens.domain.model.common.SemanticProviderType

/**
 * Single app-level profile for runtime tuning.
 *
 * Swap this profile to change camera source resolution, model assets, and
 * pipeline cadence together for different phone performance tiers.
 */
data class SailensRuntimeProfile(
    val name: String,
    val targetHardwareProfile: String,
    val camera: CameraRuntimeConfig,
    val semanticModel: YOLO26SemModelConfig,
    val instanceModel: YOLO26SegModelConfig,
    val perception: PerceptionConfig,
    val analysis: AnalysisConfig,
    val pipelineBudget: PipelinePerformanceBudget,
) {
    companion object {
        fun balanced(): SailensRuntimeProfile {
            val name = "balanced"
            val targetHardwareProfile = "snapdragon_8_gen_3_plus"
            return SailensRuntimeProfile(
                name = name,
                targetHardwareProfile = targetHardwareProfile,
                camera = CameraRuntimeConfig(
                    previewWidth = 1280,
                    previewHeight = 720,
                    analysisWidth = 960,
                    analysisHeight = 540,
                ),
                semanticModel = YOLO26SemModelConfig(
                    assetPath = "yolo26n-sem-640_int8.tflite",
                ),
                instanceModel = YOLO26SegModelConfig(
                    assetPath = "yolo26n-seg-640_int8.tflite",
                    enableMaskReconstruction = false,
                ),
                perception = PerceptionConfig(
                    runtimeProfileName = name,
                    targetHardwareProfile = targetHardwareProfile,
                    mode = PerceptionMode.COMBINED,
                    semanticProviderType = SemanticProviderType.YOLO26_SEM,
                    instanceProviderType = InstanceProviderType.YOLO26_SEG,
                    inferenceStrategy = InferenceStrategy.ALTERNATING,
                    enableSemanticFrameSkipping = false,
                    semanticFrameInterval = 2,
                ),
                analysis = AnalysisConfig(),
                pipelineBudget = PipelinePerformanceBudget(),
            )
        }

        fun lowLatency(): SailensRuntimeProfile {
            val base = balanced()
            return base.copy(
                name = "low_latency",
                camera = base.camera.copy(
                    analysisWidth = 640,
                    analysisHeight = 360,
                ),
                perception = base.perception.copy(
                    runtimeProfileName = "low_latency",
                    enableSemanticFrameSkipping = true,
                    semanticFrameInterval = 2,
                ),
            )
        }
    }
}
