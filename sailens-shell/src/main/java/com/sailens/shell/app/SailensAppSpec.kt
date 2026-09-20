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
 * Both checks must be cheap. Preflight decides whether a control should exist at all, and it runs
 * before the user has asked for anything -- it must not create a compiled model, initialise a
 * GPU/NPU delegate or run a probe inference to answer (§5.2). That work stays at session start,
 * where its cost is something the user asked for.
 */
data class GuidanceSpec(
    /** Does the configured semantic model source resolve? Resolving is not loading. */
    val semanticModelPresent: () -> Boolean,
    /** Do the declared taxonomy id and class count agree with the navigation semantics? */
    val taxonomyCompatible: () -> Boolean,
)

/** Describe's static configuration: is an engine wired and does it have a model bundle? */
data class DescribeSpec(
    val engineAvailable: () -> Boolean,
)

/**
 * What this edition promises, as opposed to what it merely offers.
 *
 * This is edition-level validation, not a framework restriction (§5.2). sailens-yolo may declare
 * Guidance required because that is the product it ships; the platform itself is happy with zero
 * pipelines.
 */
data class CapabilityExpectations(
    val guidanceRequired: Boolean = false,
    val describeRequired: Boolean = false,
)
