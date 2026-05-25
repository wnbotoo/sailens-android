package com.friady.sailens.presentation.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneOverlayConfigTest {
    @Test
    fun `initial mode falls back to the first enabled overlay mode`() {
        val config = SceneOverlayConfig(
            enablePassableAreaMaskOverlay = false,
            enableSemanticClassMaskOverlay = true,
            enableDetectionOverlay = false,
            enableDebugPanel = false,
            initialMode = SceneOverlayMode.DETECTION_BOXES,
        )

        assertEquals(setOf(SceneOverlayMode.SEMANTIC_CLASS_MASK), config.enabledOverlayModes)
        assertEquals(SceneOverlayMode.SEMANTIC_CLASS_MASK, config.effectiveInitialMode)
        assertEquals(SceneOverlayMode.SEMANTIC_CLASS_MASK, config.coerceEnabledMode(SceneOverlayMode.DETECTION_BOXES))
    }

    @Test
    fun `all disabled overlay modes fall back to off`() {
        val config = SceneOverlayConfig(
            enablePassableAreaMaskOverlay = false,
            enableSemanticClassMaskOverlay = false,
            enableDetectionOverlay = false,
            enableDebugPanel = false,
        )

        assertEquals(emptySet<SceneOverlayMode>(), config.enabledOverlayModes)
        assertEquals(SceneOverlayMode.OFF, config.effectiveInitialMode)
        assertTrue(config.isModeEnabled(SceneOverlayMode.OFF))
        assertFalse(config.isModeEnabled(SceneOverlayMode.PASSABLE_AREA_MASK))
    }
}
