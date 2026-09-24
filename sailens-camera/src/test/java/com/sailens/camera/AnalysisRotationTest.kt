package com.sailens.camera

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisRotationTest {

    @Test
    fun `a phone clearly held in each orientation gets that rotation`() {
        assertEquals(Surface.ROTATION_0, AnalysisRotation.targetRotationFor(5, Surface.ROTATION_90))
        assertEquals(Surface.ROTATION_270, AnalysisRotation.targetRotationFor(95, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_180, AnalysisRotation.targetRotationFor(185, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_90, AnalysisRotation.targetRotationFor(265, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_0, AnalysisRotation.targetRotationFor(350, Surface.ROTATION_90))
    }

    @Test
    fun `near the 45 degree boundary the current rotation is kept`() {
        // Walking with the phone tilted to ~45 degrees must not flip the frames back and forth.
        assertEquals(Surface.ROTATION_0, AnalysisRotation.targetRotationFor(44, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_0, AnalysisRotation.targetRotationFor(50, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_270, AnalysisRotation.targetRotationFor(40, Surface.ROTATION_270))
    }

    @Test
    fun `an unknown orientation keeps the current rotation`() {
        assertEquals(Surface.ROTATION_90, AnalysisRotation.targetRotationFor(-1, Surface.ROTATION_90))
    }
}
