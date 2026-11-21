package com.sailens.shell.di

import com.sailens.core.frame.PipelineTimingConfig
import com.sailens.guidance.config.GuidanceOptimizationConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineExperimentTest {
    @Test
    fun presetsPreserveTraceLabelsAndSampling() {
        val noOptimizations = GuidanceOptimizationConfig()
        val semanticProducer = GuidanceOptimizationConfig(preferSemanticPreprocess = true)
        val semanticNeon = semanticProducer.copy(useNeonArgmax = true)
        val combined = semanticNeon.copy(directFloatNchw = true)
        val indexed = combined.copy(precomputeNearestIndices = true)

        val presets = listOf(
            Triple(null, PipelineTimingConfig(), noOptimizations),
            Triple("unknown", PipelineTimingConfig(), noOptimizations),
            Triple("race", PipelineTimingConfig("race", sampleSemanticPixels = true), noOptimizations),
            Triple("race-unsampled", PipelineTimingConfig("race-unsampled"), noOptimizations),
            Triple("sem-first", PipelineTimingConfig("sem-first", sampleSemanticPixels = true), semanticProducer),
            Triple("sem-scalar", PipelineTimingConfig("sem-scalar"), semanticProducer),
            Triple("sem-simd", PipelineTimingConfig("sem-simd"), semanticNeon),
            Triple("sem-nchw", PipelineTimingConfig("sem-nchw"), combined),
            Triple("sem-nchw-untimed", PipelineTimingConfig(), combined),
            Triple(
                "sem-nchw-indexed",
                PipelineTimingConfig("sem-nchw-indexed", measureSemanticScoreThreadCpu = true),
                indexed,
            ),
            Triple("sem-nchw-indexed-untimed", PipelineTimingConfig(), indexed),
        )

        for ((marker, timing, optimizations) in presets) {
            val experiment = PipelineExperiment.fromMarker(marker)
            assertEquals(marker, timing, experiment.timing)
            assertEquals(marker, optimizations, experiment.optimizations)
        }
    }
}
