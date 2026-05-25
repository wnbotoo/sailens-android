package com.friady.sailens.app

import com.friady.sailens.presentation.scene.SceneOverlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SailensRuntimeProfileTest {
    @Test
    fun `balanced profile keeps selected values in sync`() {
        val profile = SailensRuntimeProfile.balanced()

        assertEquals("balanced", profile.name)
        assertEquals(960, profile.camera.analysisWidth)
        assertEquals(540, profile.camera.analysisHeight)
        assertEquals("balanced", profile.perception.runtimeProfileName)
        assertEquals(profile.targetHardwareProfile, profile.perception.targetHardwareProfile)
        assertFalse(profile.perception.enableSemanticFrameSkipping)
        assertTrue(profile.sceneOverlay.enablePassableAreaMaskOverlay)
        assertTrue(profile.sceneOverlay.enableSemanticClassMaskOverlay)
        assertTrue(profile.sceneOverlay.enableDetectionOverlay)
        assertTrue(profile.sceneOverlay.enableDebugPanel)
        assertEquals(SceneOverlayMode.PASSABLE_AREA_MASK, profile.sceneOverlay.initialMode)
    }

    @Test
    fun `low latency profile lowers camera analysis resolution and enables semantic reuse`() {
        val profile = SailensRuntimeProfile.lowLatency()

        assertEquals("low_latency", profile.name)
        assertEquals(640, profile.camera.analysisWidth)
        assertEquals(360, profile.camera.analysisHeight)
        assertEquals("low_latency", profile.perception.runtimeProfileName)
        assertTrue(profile.perception.enableSemanticFrameSkipping)
    }
}
