package com.sailens.shell.guidance.overlay

import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.depth.geometry.DepthGeometrySnapshot
import com.sailens.guidance.depth.geometry.DepthLaneState
import com.sailens.guidance.geometry.GroundFitValidity
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.safety.CapabilityStatus
import com.sailens.shell.ext.GROUND_GEOMETRY_ABOVE
import com.sailens.shell.ext.GROUND_GEOMETRY_BELOW
import com.sailens.shell.ext.GROUND_GEOMETRY_GROUND
import com.sailens.shell.ext.groundGeometryPixels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The depth lane's debug views: the overlay's colours per label, the panel's line, the overlay mode's switch. */
class DepthDebugViewsTest {

    /** A 2×2 grid: ground, above, below, and one cell the fit labelled nothing. */
    private fun observation(validity: GroundFitValidity = GroundFitValidity.VALID): GroundObservation {
        fun mask(x: Int, y: Int) = BinaryMask(2, 2).also { it.set(x, y, true) }
        return GroundObservation(
            frameTimeNanos = 0,
            validity = validity,
            groundMask = mask(0, 0),
            aboveGroundMask = mask(1, 0),
            belowGroundMask = mask(0, 1),
            disparityScale = 7.5f,
            disparityShift = 0.2f,
            inlierRatio = 0.8f,
            planeAgreesWithGravity = true,
            confidence = 0.6f,
        )
    }

    @Test
    fun `ground is green, above red, below blue, and an unlabelled cell is not drawn`() {
        val pixels = IntArray(4)
        groundGeometryPixels(observation(), 0, 0, 2, 2, 2, 2, pixels)
        assertArrayEquals(intArrayOf(GROUND_GEOMETRY_GROUND, GROUND_GEOMETRY_ABOVE, GROUND_GEOMETRY_BELOW, 0), pixels)
        assertEquals(3, setOf(GROUND_GEOMETRY_GROUND, GROUND_GEOMETRY_ABOVE, GROUND_GEOMETRY_BELOW).size)
    }

    @Test
    fun `a larger output samples the cell under each pixel's centre`() {
        val pixels = IntArray(16)
        groundGeometryPixels(observation(), 0, 0, 2, 2, 4, 4, pixels)
        assertEquals(GROUND_GEOMETRY_GROUND, pixels[0])
        assertEquals(GROUND_GEOMETRY_GROUND, pixels[5])
        assertEquals(GROUND_GEOMETRY_ABOVE, pixels[3])
        assertEquals(GROUND_GEOMETRY_BELOW, pixels[12])
        assertEquals(0, pixels[15])
    }

    private fun snapshot(validity: GroundFitValidity = GroundFitValidity.VALID) = DepthGeometrySnapshot(
        generation = 1,
        state = DepthLaneState.RUNNING,
        runId = 12,
        observation = observation(validity),
        periodMs = 333,
        prepareMs = 6,
        inferMs = 31,
        fitMs = 9,
        modelShape = "1x3x392x224>1x392x224",
    )

    @Test
    fun `the panel line gives the capability, then the run, its age, the fit and the timings`() {
        assertEquals(
            "available · run 12 · age 40 ms · valid ground 25% above 25% below 25% · prepare/infer/fit 6/31/9 ms · 1x3x392x224>1x392x224",
            depthDebugText(snapshot(), CapabilityStatus.Available, 40),
        )
        assertEquals(
            "unavailable (paused_thermal) · run 12 · age - · inverse_output · prepare/infer/fit 6/31/9 ms · 1x3x392x224>1x392x224",
            depthDebugText(snapshot(GroundFitValidity.INVERSE_OUTPUT), CapabilityStatus.Unavailable(setOf("paused_thermal")), null),
        )
        assertEquals(
            "unavailable (initializing)",
            depthDebugText(DepthGeometrySnapshot(generation = 1, state = DepthLaneState.INITIALIZING, periodMs = 333), CapabilityStatus.Unavailable(setOf("initializing")), null),
        )
    }

    @Test
    fun `the ground-geometry overlay is offered with the diagnostics, and not without them`() {
        assertTrue(SceneOverlayMode.GROUND_GEOMETRY in SceneOverlayConfig.forDiagnostics(true).enabledOverlayModes)
        assertFalse(SceneOverlayMode.GROUND_GEOMETRY in SceneOverlayConfig.forDiagnostics(false).enabledOverlayModes)
        assertFalse(SceneOverlayConfig(enableGroundGeometryOverlay = false).isModeEnabled(SceneOverlayMode.GROUND_GEOMETRY))
    }
}
