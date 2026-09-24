package com.sailens.shell.guidance.overlay

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayRotationTest {

    @Test
    fun `no turn while analysis and display agree`() {
        for (rotation in listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)) {
            assertEquals(0, overlayRotationDegrees(rotation, rotation))
        }
    }

    @Test
    fun `rotation lock on, phone turned clockwise onto its side`() {
        // Display pinned at ROTATION_0; OrientationEventListener reports 90 degrees, which the
        // analysis maps to ROTATION_270 (AnalysisRotation). Landscape-upright overlay -> portrait
        // preview is a 270 degree clockwise turn.
        assertEquals(270, overlayRotationDegrees(Surface.ROTATION_270, Surface.ROTATION_0))
    }

    @Test
    fun `rotation lock on, phone turned counter-clockwise onto its side`() {
        assertEquals(90, overlayRotationDegrees(Surface.ROTATION_90, Surface.ROTATION_0))
    }

    @Test
    fun `upside down against a portrait display`() {
        assertEquals(180, overlayRotationDegrees(Surface.ROTATION_180, Surface.ROTATION_0))
    }
}
