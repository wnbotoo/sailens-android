package com.sailens.shell.design.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing and sizing tokens for the Sailens design system.
 *
 * Centralizing these keeps screens consistent and makes the app easy to retune. Touch-target and
 * type sizes are deliberately generous: Sailens targets blind / low-vision users, so reachability
 * and large hit areas take priority over visual density.
 */
object SailensDimens {
    // Spacing scale (4dp base).
    val spaceXs = 4.dp
    val spaceSm = 8.dp
    val spaceMd = 12.dp
    val spaceLg = 16.dp
    val spaceXl = 24.dp
    val spaceXxl = 32.dp

    // Layout.
    val screenPadding = 16.dp
    val cardPadding = 16.dp
    val sectionSpacing = 24.dp
    val itemSpacing = 12.dp

    /** Minimum interactive target for primary / high-reach controls (a11y-first, exceeds the 48dp floor). */
    val minTouchTarget = 56.dp

    /** Minimum interactive target for secondary controls (Material/WCAG 48dp floor). */
    val minTouchTargetCompact = 48.dp

    /** The main "Start / Stop guidance" action — oversized so it is easy to find and press. */
    val primaryButtonHeight = 64.dp

    // Corner radii (mirror SailensShapes for ad-hoc use).
    val cornerSm = 8.dp
    val cornerMd = 16.dp
    val cornerLg = 20.dp
}
