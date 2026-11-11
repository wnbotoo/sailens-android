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
import com.sailens.shell.ext.GroundGeometrySize
import com.sailens.shell.ext.groundGeometryOverlay
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
        groundGeometryPixels(observation(), 2, 2, pixels)
        assertArrayEquals(intArrayOf(GROUND_GEOMETRY_GROUND, GROUND_GEOMETRY_ABOVE, GROUND_GEOMETRY_BELOW, 0), pixels)
        assertEquals(3, setOf(GROUND_GEOMETRY_GROUND, GROUND_GEOMETRY_ABOVE, GROUND_GEOMETRY_BELOW).size)
    }

    @Test
    fun `a larger output samples the cell under each pixel's centre`() {
        val pixels = IntArray(16)
        groundGeometryPixels(observation(), 4, 4, pixels)
        assertEquals(GROUND_GEOMETRY_GROUND, pixels[0])
        assertEquals(GROUND_GEOMETRY_GROUND, pixels[5])
        assertEquals(GROUND_GEOMETRY_ABOVE, pixels[3])
        assertEquals(GROUND_GEOMETRY_BELOW, pixels[12])
        assertEquals(0, pixels[15])
    }

    /** The fit's 90×160 grid marked along its edges: ground on the top row, below ground on the bottom row, above on both sides. */
    private fun markedGrid(): GroundObservation {
        val ground = BinaryMask(90, 160)
        val above = BinaryMask(90, 160)
        val below = BinaryMask(90, 160)
        for (x in 0 until 90) {
            ground.set(x, 0, true)
            below.set(x, 159, true)
        }
        for (y in 1 until 159) {
            above.set(0, y, true)
            above.set(89, y, true)
        }
        return observation().copy(groundMask = ground, aboveGroundMask = above, belowGroundMask = below)
    }

    /** The overlay on a frame of [sourceAspectRatio] is [width] × [height] and holds every row and column of the grid. */
    private fun assertWholeGrid(sourceAspectRatio: Float?, width: Int, height: Int) {
        val (size, pixels) = groundGeometryOverlay(markedGrid(), sourceAspectRatio, 720) { IntArray(it) }
        assertEquals(GroundGeometrySize(width, height), size)
        fun at(x: Int, y: Int) = pixels[y * size.width + x]
        // The grid's first and last rows and columns are the overlay's edges: nothing was cropped away.
        assertEquals(GROUND_GEOMETRY_GROUND, at(size.width / 2, 0))
        assertEquals(GROUND_GEOMETRY_BELOW, at(size.width / 2, size.height - 1))
        assertEquals(GROUND_GEOMETRY_ABOVE, at(0, size.height / 2))
        assertEquals(GROUND_GEOMETRY_ABOVE, at(size.width - 1, size.height / 2))
        // The bottom row is below ground all the way across.
        val bottom = (0 until size.width).count { at(it, size.height - 1) == GROUND_GEOMETRY_BELOW }
        assertEquals(size.width, bottom)
        // Each of the 160 rows reaches at least one row of pixels: no label is lost.
        val rows = (0 until size.height).map { y -> ((y + 0.5f) * 160 / size.height).toInt() }.toSet()
        assertEquals((0 until 160).toSet(), rows)
    }

    @Test
    fun `the whole grid is drawn in the frame's shape, landscape, 3 by 4 or portrait`() {
        assertWholeGrid(16f / 9f, 720, 405)
        assertWholeGrid(3f / 4f, 540, 720)
        assertWholeGrid(9f / 16f, 405, 720)
        // Without the frame's size, the grid's own shape.
        assertWholeGrid(null, 405, 720)
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
