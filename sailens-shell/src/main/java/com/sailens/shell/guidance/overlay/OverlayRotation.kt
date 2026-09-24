package com.sailens.shell.guidance.overlay

/**
 * How far to turn an overlay drawn in analysis-frame coordinates so it lines up with the preview.
 *
 * Analysis frames follow the phone's physical orientation; the preview follows the display. With
 * the system rotation lock on they differ -- a phone held sideways feeds the models an upright
 * landscape frame while the preview stays portrait -- and an overlay drawn without this lands
 * rotated against the picture under it.
 *
 * CameraX makes a frame upright for a target rotation `r` by turning the sensor image clockwise by
 * `sensor - degrees(r)`. Going from the analysis-upright image (target `analysis`) to the
 * preview-upright one (target `display`) is therefore a clockwise turn of
 * `degrees(analysis) - degrees(display)`.
 *
 * @param analysisSurfaceRotation the analysis use case's `Surface.ROTATION_*`.
 * @param displaySurfaceRotation the display's `Surface.ROTATION_*`.
 * @return clockwise degrees: 0, 90, 180 or 270.
 */
fun overlayRotationDegrees(analysisSurfaceRotation: Int, displaySurfaceRotation: Int): Int =
    Math.floorMod(analysisSurfaceRotation - displaySurfaceRotation, 4) * 90
