package com.sailens.shell.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability model from architecture.md §5.2.
 *
 * The distinctions these tests defend are the ones that decide whether a blind user is shown a
 * dead control, a crash loop, or an honest explanation. All four pipeline combinations are legal,
 * and "not configured" is not a failure.
 */
class PipelinePreflightTest {

    @Test
    fun `an empty spec configures nothing and fails nothing`() {
        val capabilities = PipelinePreflight.evaluate(SailensAppSpec())

        assertEquals(PipelineAvailability.NotConfigured, capabilities.guidance)
        assertEquals(PipelineAvailability.NotConfigured, capabilities.describe)
        assertTrue(capabilities.hasNoAvailablePipeline)
        assertNull(
            "nothing was promised, so nothing is broken",
            capabilities.configurationFailure(CapabilityExpectations()),
        )
    }

    @Test
    fun `all four combinations are legal`() {
        val combinations = listOf(
            null to null,
            guidanceSpec(present = true) to null,
            null to describeSpec(available = true),
            guidanceSpec(present = true) to describeSpec(available = true),
        )

        for ((guidance, describe) in combinations) {
            val capabilities = PipelinePreflight.evaluate(
                SailensAppSpec(guidance = guidance, describe = describe)
            )
            assertNull(
                "combination guidance=${guidance != null} describe=${describe != null} " +
                    "should not be a configuration failure when nothing is required",
                capabilities.configurationFailure(CapabilityExpectations()),
            )
        }
    }

    @Test
    fun `a missing model makes guidance unavailable, not merely unconfigured`() {
        val capabilities = PipelinePreflight.evaluate(
            SailensAppSpec(guidance = guidanceSpec(present = false))
        )

        assertEquals(
            PipelineAvailability.Unavailable(StaticUnavailableReason.ModelSourceMissing),
            capabilities.guidance,
        )
    }

    @Test
    fun `incompatible semantics make guidance unavailable rather than falling back`() {
        // There is no neutral fallback on purpose (§6.2): running with the wrong class definitions
        // would describe the scene confidently and wrongly to someone who cannot check.
        val capabilities = PipelinePreflight.evaluate(
            SailensAppSpec(guidance = guidanceSpec(present = true, taxonomyOk = false))
        )

        assertEquals(
            PipelineAvailability.Unavailable(StaticUnavailableReason.TaxonomyIncompatible),
            capabilities.guidance,
        )
    }

    @Test
    fun `a promised pipeline that is unavailable is a configuration failure`() {
        val capabilities = PipelinePreflight.evaluate(
            SailensAppSpec(guidance = guidanceSpec(present = false))
        )

        val failure = capabilities.configurationFailure(
            CapabilityExpectations(guidanceRequired = true)
        )

        assertEquals(SailensCapabilities.Pipeline.GUIDANCE, failure?.pipeline)
    }

    @Test
    fun `a pipeline that was never promised is not a configuration failure`() {
        // A ships zero weights and promises nothing, so a fresh install lands on the
        // zero-pipeline state rather than a fatal one.
        val capabilities = PipelinePreflight.evaluate(
            SailensAppSpec(guidance = guidanceSpec(present = false))
        )

        assertNull(capabilities.configurationFailure(CapabilityExpectations()))
        assertTrue(capabilities.hasNoAvailablePipeline)
    }

    @Test
    fun `preflight does not evaluate a pipeline the edition did not configure`() {
        var called = false
        val capabilities = PipelinePreflight.evaluate(
            SailensAppSpec(
                guidance = null,
                describe = DescribeSpec(engineAvailable = { called = true; true }),
            )
        )

        assertTrue(capabilities.describe.isAvailable)
        assertTrue("a configured spec's predicate should run", called)
    }

    private fun guidanceSpec(present: Boolean, taxonomyOk: Boolean = true) = GuidanceSpec(
        semanticModelPresent = { present },
        taxonomyCompatible = { taxonomyOk },
    )

    private fun describeSpec(available: Boolean) = DescribeSpec(
        engineAvailable = { available },
    )
}
