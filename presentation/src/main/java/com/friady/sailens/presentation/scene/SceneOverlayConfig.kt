package com.friady.sailens.presentation.scene

data class SceneOverlayConfig(
    val enablePassableAreaMaskOverlay: Boolean = true,
    val enableSemanticClassMaskOverlay: Boolean = true,
    val enableDetectionOverlay: Boolean = true,
    val enableDebugPanel: Boolean = true,
    val initialMode: SceneOverlayMode = SceneOverlayMode.PASSABLE_AREA_MASK,
    val bitmapRenderIntervalMs: Long = 250L,
) {
    init {
        require(bitmapRenderIntervalMs > 0) {
            "Overlay bitmap render interval must be positive, got $bitmapRenderIntervalMs"
        }
    }

    val enabledOverlayModes: Set<SceneOverlayMode>
        get() = buildSet {
            if (enablePassableAreaMaskOverlay) add(SceneOverlayMode.PASSABLE_AREA_MASK)
            if (enableSemanticClassMaskOverlay) add(SceneOverlayMode.SEMANTIC_CLASS_MASK)
            if (enableDetectionOverlay) add(SceneOverlayMode.DETECTION_BOXES)
            if (enableDebugPanel) add(SceneOverlayMode.INSTANCE_DEBUG)
        }

    val effectiveInitialMode: SceneOverlayMode
        get() = coerceEnabledMode(initialMode)

    fun coerceEnabledMode(mode: SceneOverlayMode): SceneOverlayMode {
        return if (isModeEnabled(mode)) {
            mode
        } else {
            overlayModePriority.firstOrNull(::isModeEnabled) ?: SceneOverlayMode.OFF
        }
    }

    fun isModeEnabled(mode: SceneOverlayMode): Boolean {
        return mode == SceneOverlayMode.OFF || mode in enabledOverlayModes
    }

    companion object {
        private val overlayModePriority = listOf(
            SceneOverlayMode.PASSABLE_AREA_MASK,
            SceneOverlayMode.SEMANTIC_CLASS_MASK,
            SceneOverlayMode.DETECTION_BOXES,
            SceneOverlayMode.INSTANCE_DEBUG,
        )
    }
}
