package com.sailens.shell.app

/**
 * Whether a pipeline could run, decided by a cheap static check.
 *
 * Deliberately **not** "runtime ready" (architecture.md §5.2). Available means the wiring and the
 * model source are there, nothing more. A pipeline can pass preflight and still fail when the
 * real session starts on a particular device -- a GPU delegate that will not initialise, an
 * output buffer that will not allocate. That is a [PipelineRuntimeState.Failed], not a
 * contradiction of Available, and it keeps its existing retryable path.
 */
sealed interface PipelineAvailability {

    /** The edition did not ask for this pipeline. Not an error. */
    data object NotConfigured : PipelineAvailability

    data object Available : PipelineAvailability

    data class Unavailable(val reason: StaticUnavailableReason) : PipelineAvailability

    val isAvailable: Boolean get() = this is Available
}

/** Why a configured pipeline cannot run, as far as a static check can tell. */
sealed interface StaticUnavailableReason {

    /** The configured model source does not resolve. Usually: no weights in this build. */
    data object ModelSourceMissing : StaticUnavailableReason

    /**
     * The model declares a taxonomy the navigation semantics were not written for.
     *
     * There is no neutral fallback here on purpose (§6.2). Running anyway would mean reading the
     * scene through the wrong class definitions and describing it confidently to someone who
     * cannot check.
     */
    data object TaxonomyIncompatible : StaticUnavailableReason

    /** No engine implementation is wired, or it has no model bundle. */
    data object EngineUnavailable : StaticUnavailableReason
}

/** The real session lifecycle, after the user has asked for work. */
sealed interface PipelineRuntimeState {
    data object NotStarted : PipelineRuntimeState
    data object Initializing : PipelineRuntimeState
    data object Running : PipelineRuntimeState
    data class Failed(val reason: String) : PipelineRuntimeState
}
