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
 * Which screen this edition opens on.
 *
 * Extracted from the composable so the routing rule is a value that can be asserted, not a shape
 * that only exists once a NavDisplay has been built. All four combinations land somewhere
 * different, and "Describe-only opens the guidance screen" is the bug this makes impossible to
 * reintroduce silently.
 */
enum class SailensStartDestination {
    /** Live navigation. Offers Describe as well when it is available. */
    GUIDANCE,

    /** Describe on its own: no navigation session to attach it to. */
    DESCRIBE,

    /** Nothing is available. Legal, and says so (§5.1). */
    NONE,
}

val SailensCapabilities.startDestination: SailensStartDestination
    get() = when {
        guidance.isAvailable -> SailensStartDestination.GUIDANCE
        describe.isAvailable -> SailensStartDestination.DESCRIBE
        else -> SailensStartDestination.NONE
    }

/**
 * Whether the guidance screen should offer a Describe control.
 *
 * False means the control is absent, not disabled: for someone navigating by touch exploration, a
 * control that cannot answer costs a focus stop and a press to discover it is dead.
 */
val SailensCapabilities.offersDescribeAction: Boolean
    get() = describe.isAvailable

/**
 * Evaluates a [SailensAppSpec] into [SailensCapabilities].
 *
 * A thin mapper on purpose: it runs the edition's own checks and turns their verdict into the
 * shell's vocabulary. The checks themselves belong to whoever knows what a usable model is
 * (§6.11), and must stay cheap — see the note on [GuidanceSpec]. If one of them ever starts
 * compiling a model or opening a delegate, this stops being preflight and becomes a slow,
 * surprising cold start.
 */
object PipelinePreflight {

    fun evaluate(spec: SailensAppSpec): SailensCapabilities = SailensCapabilities(
        guidance = availability(spec.guidance?.let(::guidanceCheck), configured = spec.guidance != null),
        describe = availability(spec.describe?.verifyEngine, configured = spec.describe != null),
    )

    /** The semantic model first -- Guidance cannot run without it -- then the detector, if vouched for. */
    private fun guidanceCheck(guidance: GuidanceSpec): () -> StaticUnavailableReason? = {
        guidance.verifySemanticModel() ?: guidance.verifyObstacleModel?.invoke()
    }

    private fun availability(
        verify: (() -> StaticUnavailableReason?)?,
        configured: Boolean,
    ): PipelineAvailability {
        if (!configured || verify == null) return PipelineAvailability.NotConfigured
        val reason = verify() ?: return PipelineAvailability.Available
        return PipelineAvailability.Unavailable(reason)
    }
}
