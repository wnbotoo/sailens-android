package com.sailens.shell.ext

import com.sailens.guidance.geometry.GroundObservation
import kotlin.math.roundToInt

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

/** The overlay's size in pixels. */
internal data class GroundGeometrySize(val width: Int, val height: Int)

/**
 * The overlay's size for a camera frame of [sourceAspectRatio] (width / height; the grid's own when unknown), its long
 * side [maxLongSide]. The label grid covers the whole analysis frame whatever the frame's shape -- cell (x, y) spans the
 * frame's fraction [x / width, (x + 1) / width) across and likewise down -- so the overlay takes the frame's shape and
 * keeps every cell. It is never cropped to the grid's own aspect.
 */
internal fun groundGeometrySize(gridWidth: Int, gridHeight: Int, sourceAspectRatio: Float?, maxLongSide: Int): GroundGeometrySize {
    val aspect = sourceAspectRatio?.takeIf { it > 0f && it.isFinite() } ?: (gridWidth.toFloat() / gridHeight)
    return if (aspect >= 1f) {
        GroundGeometrySize(maxLongSide, (maxLongSide / aspect).roundToInt().coerceAtLeast(1))
    } else {
        GroundGeometrySize((maxLongSide * aspect).roundToInt().coerceAtLeast(1), maxLongSide)
    }
}

/**
 * The overlay for [observation] on a frame of [sourceAspectRatio]: its size, and its pixels, row-major, in the array
 * [pixels] gives for that many. The Bitmap around them is the renderer's.
 */
internal fun groundGeometryOverlay(
    observation: GroundObservation,
    sourceAspectRatio: Float?,
    maxLongSide: Int,
    pixels: (Int) -> IntArray,
): Pair<GroundGeometrySize, IntArray> {
    val grid = observation.groundMask
    val size = groundGeometrySize(grid.width, grid.height, sourceAspectRatio, maxLongSide)
    val into = pixels(size.width * size.height)
    groundGeometryPixels(observation, size.width, size.height, into)
    return size to into
}

/** The whole label grid, stretched to [outputWidth] × [outputHeight] and sampled at output-pixel centres into [into], row-major. */
internal fun groundGeometryPixels(
    observation: GroundObservation,
    outputWidth: Int,
    outputHeight: Int,
    into: IntArray,
) {
    val grid = observation.groundMask
    for (y in 0 until outputHeight) {
        val sourceY = ((y + 0.5f) * grid.height / outputHeight).toInt().coerceIn(0, grid.height - 1)
        val rowOffset = y * outputWidth
        for (x in 0 until outputWidth) {
            val sourceX = ((x + 0.5f) * grid.width / outputWidth).toInt().coerceIn(0, grid.width - 1)
            into[rowOffset + x] = groundGeometryColor(observation, sourceX, sourceY)
        }
    }
}
