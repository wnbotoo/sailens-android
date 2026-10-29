package com.sailens.guidance.model.analysis

/**
 * What Guidance cannot judge in this frame and must tell the user about (det-first plan D5): exactly the
 * states it generates the SENSOR_QUALITY and GROUND_UNRECOGNIZED events for, before any cooldown.
 *
 * Those events are throttled by the cooldown and offered only when they are the frame's first, so the
 * shell cannot read from them whether a state still holds. It can from this, every frame.
 *
 * @param frameQuality the debounced frame quality; [FrameQuality.OK] while sensor-quality events are off.
 * @param groundUnrecognized the floor is confirmed unrecognised ([GroundRecognition.UNRECOGNIZED]).
 */
data class VisionFaults(
    val frameQuality: FrameQuality = FrameQuality.OK,
    val groundUnrecognized: Boolean = false,
) {
    companion object {
        val NONE: VisionFaults = VisionFaults()
    }
}
