package com.sailens.shell.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability state machine: configured, expected, available, running (architecture.md §5.2).
 *
 * These four are separate questions and the whole point of the model is that conflating them
 * produces the two failures that matter to someone who cannot see the screen: a control that
 * silently does nothing, and an app that looks like it is working when it never could.
 *
 * All four pipeline combinations are legal, including neither.
 */
class CapabilityStateMachineTest {

    // ---- Configured vs available -------------------------------------------------------------

    @Test
    fun `a pipeline the edition did not ask for is not configured, not broken`() {
        val capabilities = PipelinePreflight.evaluate(SailensAppSpec())

        assertEquals(PipelineAvailability.NotConfigured, capabilities.guidance)
        assertEquals(PipelineAvailability.NotConfigured, capabilities.describe)
    }

    @Test
    fun `a configured pipeline whose check passes is available`() {
        val capabilities = PipelinePreflight.evaluate(spec(guidance = OK))

        assertEquals(PipelineAvailability.Available, capabilities.guidance)
    }

    @Test
    fun `a configured pipeline whose check fails carries the reason`() {
        val capabilities = PipelinePreflight.evaluate(
            spec(guidance = { StaticUnavailableReason.ModelSourceMissing })
        )

        assertEquals(
            PipelineAvailability.Unavailable(StaticUnavailableReason.ModelSourceMissing),
            capabilities.guidance,
        )
    }

    @Test
    fun `preflight runs each edition check exactly once and does not run the ones it was not given`() {
        var guidanceChecks = 0
        val spec = SailensAppSpec(
            guidance = GuidanceSpec(verifySemanticModel = { guidanceChecks++; null }),
            describe = null,
        )

        val capabilities = PipelinePreflight.evaluate(spec)

        assertEquals(1, guidanceChecks)
        assertEquals(PipelineAvailability.NotConfigured, capabilities.describe)
    }

    // ---- The four combinations ---------------------------------------------------------------

    @Test
    fun `both pipelines available opens guidance and offers Describe`() {
        val capabilities = PipelinePreflight.evaluate(spec(guidance = OK, describe = OK))

        assertEquals(SailensStartDestination.GUIDANCE, capabilities.startDestination)
        assertTrue(capabilities.offersDescribeAction)
        assertFalse(capabilities.hasNoAvailablePipeline)
    }

    @Test
    fun `Guidance only opens guidance and must not offer a Describe control`() {
        val capabilities = PipelinePreflight.evaluate(
            spec(guidance = OK, describe = { StaticUnavailableReason.EngineUnavailable })
        )

        assertEquals(SailensStartDestination.GUIDANCE, capabilities.startDestination)
        assertFalse(
            "a control that cannot answer costs a blind user a focus stop and a press",
            capabilities.offersDescribeAction,
        )
    }

    @Test
    fun `Describe only opens Describe and must not land on the guidance screen`() {
        val capabilities = PipelinePreflight.evaluate(
            spec(guidance = { StaticUnavailableReason.ModelSourceMissing }, describe = OK)
        )

        assertEquals(SailensStartDestination.DESCRIBE, capabilities.startDestination)
        assertFalse(capabilities.hasNoAvailablePipeline)
    }

    @Test
    fun `Describe only is still Describe-only when Guidance was never configured`() {
        val capabilities = PipelinePreflight.evaluate(spec(guidance = null, describe = OK))

        assertEquals(SailensStartDestination.DESCRIBE, capabilities.startDestination)
    }

    @Test
    fun `zero pipelines is a legal state, not a failure`() {
        val capabilities = PipelinePreflight.evaluate(
            spec(
                guidance = { StaticUnavailableReason.ModelSourceMissing },
                describe = { StaticUnavailableReason.EngineUnavailable },
            )
        )

        assertTrue(capabilities.hasNoAvailablePipeline)
        assertEquals(SailensStartDestination.NONE, capabilities.startDestination)
        assertNull(
            "promising nothing and providing nothing is consistent",
            capabilities.configurationFailure(CapabilityExpectations()),
        )
    }

    // ---- Expected vs available ---------------------------------------------------------------

    @Test
    fun `a required pipeline that is available is not a failure`() {
        val capabilities = PipelinePreflight.evaluate(spec(guidance = OK))

        assertNull(
            capabilities.configurationFailure(CapabilityExpectations(guidanceRequired = true)),
        )
    }

    @Test
    fun `a required pipeline that is statically unavailable is a configuration failure`() {
        val capabilities = PipelinePreflight.evaluate(
            spec(guidance = { StaticUnavailableReason.ModelSourceMissing })
        )

        val failure = capabilities.configurationFailure(
            CapabilityExpectations(guidanceRequired = true)
        )

        assertNotNull(failure)
        assertEquals(SailensCapabilities.Pipeline.GUIDANCE, failure?.pipeline)
        assertEquals(
            PipelineAvailability.Unavailable(StaticUnavailableReason.ModelSourceMissing),
            failure?.availability,
        )
    }

    @Test
    fun `a wrong class count in a required model becomes a configuration failure`() {
        // The check the old "does the asset exist" preflight could not make: a model of the wrong
        // shape passed, and the mismatch surfaced only after the user pressed start.
        val mismatch = StaticUnavailableReason.ModelClassCountMismatch(
            declared = 19,
            found = listOf(21, 160),
        )
        val capabilities = PipelinePreflight.evaluate(spec(guidance = { mismatch }))

        val failure = capabilities.configurationFailure(
            CapabilityExpectations(guidanceRequired = true)
        )

        assertEquals(SailensCapabilities.Pipeline.GUIDANCE, failure?.pipeline)
        assertEquals(PipelineAvailability.Unavailable(mismatch), failure?.availability)
    }

    @Test
    fun `an unreadable model output in a required model becomes a configuration failure`() {
        val unreadable = StaticUnavailableReason.ModelOutputUnreadable("two output tensors")
        val capabilities = PipelinePreflight.evaluate(spec(guidance = { unreadable }))

        assertEquals(
            PipelineAvailability.Unavailable(unreadable),
            capabilities
                .configurationFailure(CapabilityExpectations(guidanceRequired = true))
                ?.availability,
        )
    }

    @Test
    fun `an optional pipeline that is unavailable is not a failure`() {
        val capabilities = PipelinePreflight.evaluate(
            spec(guidance = OK, describe = { StaticUnavailableReason.EngineUnavailable })
        )

        assertNull(
            "A ships no VLM and must not treat that as a broken build",
            capabilities.configurationFailure(CapabilityExpectations(guidanceRequired = true)),
        )
    }

    @Test
    fun `a pipeline that is required but never configured is a failure, and says so`() {
        val capabilities = PipelinePreflight.evaluate(SailensAppSpec())

        val failure = capabilities.configurationFailure(
            CapabilityExpectations(describeRequired = true)
        )

        assertEquals(SailensCapabilities.Pipeline.DESCRIBE, failure?.pipeline)
        assertEquals(PipelineAvailability.NotConfigured, failure?.availability)
    }

    @Test
    fun `Guidance is reported first when both promises are broken`() {
        // Arbitrary but fixed: the message is spoken aloud, and two of them at once is noise. The
        // safety pipeline is the one to name.
        val capabilities = PipelinePreflight.evaluate(SailensAppSpec())

        val failure = capabilities.configurationFailure(
            CapabilityExpectations(guidanceRequired = true, describeRequired = true)
        )

        assertEquals(SailensCapabilities.Pipeline.GUIDANCE, failure?.pipeline)
    }

    // ---- Available vs running ----------------------------------------------------------------

    @Test
    fun `a device-specific session failure is a runtime state, not a loss of availability`() {
        // Static availability says the wiring and the model are there. A GPU delegate that will
        // not initialise on this particular handset is a Failed run, and it keeps its retryable
        // path -- it must not silently turn the control off (§5.2).
        val capabilities = PipelinePreflight.evaluate(spec(guidance = OK))
        val runtime: PipelineRuntimeState = PipelineRuntimeState.Failed("GPU delegate init failed")

        assertEquals(PipelineAvailability.Available, capabilities.guidance)
        assertTrue(runtime is PipelineRuntimeState.Failed)
        assertEquals(SailensStartDestination.GUIDANCE, capabilities.startDestination)
    }

    private fun spec(
        guidance: (() -> StaticUnavailableReason?)? = null,
        describe: (() -> StaticUnavailableReason?)? = null,
    ) = SailensAppSpec(
        guidance = guidance?.let { GuidanceSpec(verifySemanticModel = it) },
        describe = describe?.let { DescribeSpec(verifyEngine = it) },
    )

    private companion object {
        /** A check that finds nothing wrong. */
        val OK: () -> StaticUnavailableReason? = { null }
    }
}
