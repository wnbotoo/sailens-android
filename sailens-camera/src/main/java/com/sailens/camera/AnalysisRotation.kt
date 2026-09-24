package com.sailens.camera

import android.view.Surface

/**
 * Which way up the analysis frames should be, from how the phone is physically held.
 *
 * CameraX rotates analysis frames relative to the use case's target rotation, which defaults to
 * the display rotation at the moment the use case was built. That is the wrong reference for
 * Guidance twice over: the view model that owns the use case outlives an activity rotation, so the
 * rotation goes stale; and with the system rotation lock on, the display never rotates at all. A
 * phone turned on its side then feeds the models a world lying on its side. Blind users do not
 * look at the screen, so the physical orientation is the only one that matters.
 *
 * Hysteresis keeps a phone held near 45 degrees -- a chest mount, a tilted hand -- from flipping
 * between two rotations while walking: the rotation only changes once the device is within
 * [SNAP_DEGREES] of the new orientation.
 */
internal object AnalysisRotation {
    const val SNAP_DEGREES: Int = 30

    /**
     * @param orientationDegrees from `OrientationEventListener`: 0 upright, increasing clockwise.
     * @param current the `Surface.ROTATION_*` in use now.
     * @return the `Surface.ROTATION_*` to use, which is [current] unless the device has clearly
     *   turned.
     */
    fun targetRotationFor(orientationDegrees: Int, current: Int): Int {
        if (orientationDegrees < 0) return current
        val degrees = orientationDegrees % 360
        for ((center, rotation) in CANDIDATES) {
            val distance = minOf((degrees - center + 360) % 360, (center - degrees + 360) % 360)
            if (distance <= SNAP_DEGREES) return rotation
        }
        return current
    }

    // The device turned clockwise by `center` degrees needs the frame rotated back: a phone
    // turned 90 degrees clockwise is a display at ROTATION_270.
    private val CANDIDATES = listOf(
        0 to Surface.ROTATION_0,
        90 to Surface.ROTATION_270,
        180 to Surface.ROTATION_180,
        270 to Surface.ROTATION_90,
    )
}
