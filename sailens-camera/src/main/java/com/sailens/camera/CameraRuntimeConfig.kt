package com.sailens.camera

/**
 * Camera runtime knobs for balancing quality, latency, and thermal load.
 *
 * The ML model still reads its own input shape from the selected TFLite asset.
 * These values control the CameraX source frame size before preprocessing.
 */
data class CameraRuntimeConfig(
    val previewWidth: Int = 1280,
    val previewHeight: Int = 720,
    val analysisWidth: Int = 960,
    val analysisHeight: Int = 540,
) {
    init {
        require(previewWidth > 0 && previewHeight > 0) {
            "Preview size must be positive, got ${previewWidth}x$previewHeight"
        }
        require(analysisWidth > 0 && analysisHeight > 0) {
            "Analysis size must be positive, got ${analysisWidth}x$analysisHeight"
        }
    }
}
