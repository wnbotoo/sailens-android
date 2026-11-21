package com.sailens.guidance.config

/** Opt-in execution choices for the Guidance perception pipeline. */
data class GuidanceOptimizationConfig(
    val preferSemanticPreprocess: Boolean = false,
    val useNeonArgmax: Boolean = false,
    val directFloatNchw: Boolean = false,
    val precomputeNearestIndices: Boolean = false,
)
