package com.sailens.shell.app

/**
 * The result of preflight: what this edition can actually offer right now.
 */
data class SailensCapabilities(
    val guidance: PipelineAvailability,
    val describe: PipelineAvailability,
) {
    /** True when nothing is available to do. A legal state, not a failure (§5.1). */
    val hasNoAvailablePipeline: Boolean
        get() = !guidance.isAvailable && !describe.isAvailable

    /**
     * A pipeline the edition promised but cannot provide.
     *
     * Returns null when the edition is keeping its promises -- including the case where nothing
     * is configured and nothing was promised.
     */
    fun configurationFailure(expectations: CapabilityExpectations): ConfigurationFailure? = when {
        expectations.guidanceRequired && !guidance.isAvailable ->
            ConfigurationFailure(Pipeline.GUIDANCE, guidance)

        expectations.describeRequired && !describe.isAvailable ->
            ConfigurationFailure(Pipeline.DESCRIBE, describe)

        else -> null
    }

    enum class Pipeline { GUIDANCE, DESCRIBE }

    data class ConfigurationFailure(
        val pipeline: Pipeline,
        val availability: PipelineAvailability,
    )
}

/**
 * Evaluates a [SailensAppSpec] into [SailensCapabilities].
 *
 * Everything here is cheap by construction: it calls the spec's own predicates and nothing else.
 * If one of those predicates ever starts loading a model, this stops being preflight and starts
 * being a slow, surprising cold start -- see the note on [GuidanceSpec].
 */
object PipelinePreflight {

    fun evaluate(spec: SailensAppSpec): SailensCapabilities = SailensCapabilities(
        guidance = evaluateGuidance(spec.guidance),
        describe = evaluateDescribe(spec.describe),
    )

    private fun evaluateGuidance(spec: GuidanceSpec?): PipelineAvailability {
        if (spec == null) return PipelineAvailability.NotConfigured
        if (!spec.semanticModelPresent()) {
            return PipelineAvailability.Unavailable(StaticUnavailableReason.ModelSourceMissing)
        }
        if (!spec.taxonomyCompatible()) {
            return PipelineAvailability.Unavailable(StaticUnavailableReason.TaxonomyIncompatible)
        }
        return PipelineAvailability.Available
    }

    private fun evaluateDescribe(spec: DescribeSpec?): PipelineAvailability {
        if (spec == null) return PipelineAvailability.NotConfigured
        if (!spec.engineAvailable()) {
            return PipelineAvailability.Unavailable(StaticUnavailableReason.EngineUnavailable)
        }
        return PipelineAvailability.Available
    }
}
