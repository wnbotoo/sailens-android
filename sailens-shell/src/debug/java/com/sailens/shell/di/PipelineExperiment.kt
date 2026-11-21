package com.sailens.shell.di

import com.sailens.core.frame.PipelineTimingConfig
import com.sailens.guidance.config.GuidanceOptimizationConfig

private val semanticProducer = GuidanceOptimizationConfig(preferSemanticPreprocess = true)
private val semanticNeon = semanticProducer.copy(useNeonArgmax = true)
private val combinedOptimizations = semanticNeon.copy(directFloatNchw = true)
private val indexedOptimizations = combinedOptimizations.copy(precomputeNearestIndices = true)

/** Maps the local debug marker to independent execution and profiling settings. */
internal enum class PipelineExperiment(
    val marker: String?,
    val optimizations: GuidanceOptimizationConfig = GuidanceOptimizationConfig(),
    measure: Boolean = true,
    sampleSemanticPixels: Boolean = false,
) {
    OFF(null),
    RACE("race", sampleSemanticPixels = true),
    RACE_UNSAMPLED("race-unsampled"),
    SEM_FIRST("sem-first", semanticProducer, sampleSemanticPixels = true),
    SEM_SCALAR("sem-scalar", semanticProducer),
    SEM_SIMD("sem-simd", semanticNeon),
    SEM_NCHW("sem-nchw", combinedOptimizations),
    SEM_NCHW_UNTIMED("sem-nchw-untimed", combinedOptimizations, measure = false),
    SEM_NCHW_INDEXED("sem-nchw-indexed", indexedOptimizations),
    SEM_NCHW_INDEXED_UNTIMED("sem-nchw-indexed-untimed", indexedOptimizations, measure = false),
    ;

    val timing = PipelineTimingConfig(marker.takeIf { measure }, sampleSemanticPixels)

    companion object {
        fun fromMarker(marker: String?): PipelineExperiment = entries.firstOrNull { it.marker == marker } ?: OFF
    }
}
