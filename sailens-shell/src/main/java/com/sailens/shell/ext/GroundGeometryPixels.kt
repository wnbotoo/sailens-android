package com.sailens.shell.ext

import com.sailens.guidance.geometry.GroundObservation

// The ground-geometry overlay's pixels, apart from the Bitmap that holds them (OverlayBitmapRenderer), so that the JVM
// tests can run them: nothing here touches an Android class. ARGB, 70% opaque.
internal const val GROUND_GEOMETRY_GROUND: Int = 0xB300C853.toInt()
internal const val GROUND_GEOMETRY_ABOVE: Int = 0xB3FF1744.toInt()
internal const val GROUND_GEOMETRY_BELOW: Int = 0xB32979FF.toInt()
internal const val GROUND_GEOMETRY_UNKNOWN: Int = 0

/**
 * One cell of the ground fit's label grid as the overlay draws it: ground green, above ground red, below ground blue,
 * and nothing where the fit labelled nothing -- unknown is never drawn as anything.
 */
internal fun groundGeometryColor(observation: GroundObservation, x: Int, y: Int): Int = when {
    observation.aboveGroundMask.get(x, y) -> GROUND_GEOMETRY_ABOVE
    observation.belowGroundMask.get(x, y) -> GROUND_GEOMETRY_BELOW
    observation.groundMask.get(x, y) -> GROUND_GEOMETRY_GROUND
    else -> GROUND_GEOMETRY_UNKNOWN
}

/** The label grid's crop `[left, left + cropWidth) × [top, top + cropHeight)`, sampled at output-pixel centres into [into], row-major. */
internal fun groundGeometryPixels(
    observation: GroundObservation,
    left: Int,
    top: Int,
    cropWidth: Int,
    cropHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    into: IntArray,
) {
    val grid = observation.groundMask
    for (y in 0 until outputHeight) {
        val sourceY = (top + ((y + 0.5f) * cropHeight / outputHeight).toInt()).coerceIn(0, grid.height - 1)
        val rowOffset = y * outputWidth
        for (x in 0 until outputWidth) {
            val sourceX = (left + ((x + 0.5f) * cropWidth / outputWidth).toInt()).coerceIn(0, grid.width - 1)
            into[rowOffset + x] = groundGeometryColor(observation, sourceX, sourceY)
        }
    }
}
