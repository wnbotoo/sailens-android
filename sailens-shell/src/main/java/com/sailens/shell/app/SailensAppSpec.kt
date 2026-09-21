package com.sailens.shell.app

/**
 * What an edition intends to offer, and what it promises.
 *
 * Four separate questions live here and in [PipelineAvailability] / [PipelineRuntimeState], and
 * architecture.md §5.2 is emphatic that they must not be conflated:
 *
 *  1. **Configured** — the edition means to expose this pipeline. A null spec means it does not.
 *  2. **Expected** — the edition promises it as part of the product ([CapabilityExpectations]).
 *  3. **Availability** — a cheap static check says the implementation and model source are there.
 *  4. **Runtime state** — what actually happened once the user pressed start.
 *
 * All four combinations of the two pipelines are legal, including neither. A shell with nothing
 * configured is not broken; it is an application that has not been given a model yet, and it
 * should say so rather than pretend.
 */
data class SailensAppSpec(
    val guidance: GuidanceSpec? = null,
    val describe: DescribeSpec? = null,
    val expectations: CapabilityExpectations = CapabilityExpectations(),
)

/**
 * Guidance's static configuration.
 *
 * The shell owns the *vocabulary* of reasons; the host supplies the check, because what counts as a
 * usable model is an edition decision (§6.11). The shell does not reach into the Guidance pipeline
 * to find out.
 *
 * The check must be cheap. Preflight decides whether a control should exist at all, and it runs
 * before the user has asked for anything — it must not create a compiled model, initialise a
 * GPU/NPU delegate or run a probe inference to answer (§5.2). Reading the model file's metadata
 * tables is fine and is what the check is expected to do: "the asset exists" is not a check, since
 * an asset of the wrong shape passes it and then fails after the person has pressed start.
 */
data class GuidanceSpec(
    /** Returns null when the configured semantic model is usable, or why it is not. */
    val verifySemanticModel: () -> StaticUnavailableReason?,
)

/** Describe's static configuration: is an engine wired and does it have a model bundle? */
data class DescribeSpec(
    /** Returns null when the configured engine is usable, or why it is not. */
    val verifyEngine: () -> StaticUnavailableReason?,
)

/**
 * What this edition promises, as opposed to what it merely offers.
 *
 * This is distribution-level validation, not a framework restriction (§5.2). The official Sailens
 * app declares Guidance required because that is the product it ships; the platform itself is happy with zero
 * pipelines.
 */
data class CapabilityExpectations(
    val guidanceRequired: Boolean = false,
    val describeRequired: Boolean = false,
)
