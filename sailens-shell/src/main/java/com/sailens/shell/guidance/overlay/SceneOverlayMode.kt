package com.sailens.shell.guidance.overlay

enum class SceneOverlayMode {
    OFF,
    PASSABLE_AREA_MASK,
    SEMANTIC_CLASS_MASK,
    DETECTION_BOXES,

    /** The depth lane's latest ground fit, while its capability is fresh (docs/geometry.md §6); nothing without the lane. */
    GROUND_GEOMETRY,
}
