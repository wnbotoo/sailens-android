package com.sailens.runtime

/** Actual placement of the upright analysis image in a successfully populated input tensor. */
data class InputImageMapping(
    val imageWidth: Int,
    val imageHeight: Int,
    val tensorWidth: Int,
    val tensorHeight: Int,
    val x: Axis,
    val y: Axis,
    val rotationDegrees: Int,
) {
    /** Pixel-edge coordinates: tensor = scale * image + offset, written in [writtenStart, writtenEnd). */
    data class Axis(val scale: Double, val offset: Double, val writtenStart: Int, val writtenEnd: Int)
}

data class PreparedImage(val backend: InputPreprocessBackend, val mapping: InputImageMapping?)
