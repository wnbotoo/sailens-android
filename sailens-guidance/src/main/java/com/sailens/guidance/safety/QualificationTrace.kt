package com.sailens.guidance.safety

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.model.trace.CapabilityTrace
import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.processor.analysis.FrameQualityResult

/** [this] frame's trace with its frame quality, qualification and receipt-to-decision time filled in. */
internal fun FrameTrace.withQualification(
    quality: FrameQualityResult,
    qualification: GuidanceQualification,
    receiptToDecisionMs: Double?,
    receivedElapsedMs: Long?,
): FrameTrace = copy(
    frameQuality = quality.stabilized.name.lowercase(),
    frameQualityClassified = quality.classified.name.lowercase(),
    qualification = qualification.baseline.traceLevel,
    qualificationReasons = qualification.baseline.traceReasons,
    capabilities = qualification.capabilities.entries.associate { (capability, status) ->
        capability.name.lowercase() to CapabilityTrace(status.traceStatus, status.traceIssues)
    },
    receiptToDecisionMs = receiptToDecisionMs,
    receivedElapsedMs = receivedElapsedMs,
)

/**
 * When the source received [frame], elapsed-realtime milliseconds; null when it did not record it (0,
 * `ImageFrame.receivedElapsedRealtimeNanos`). The one conversion: the frame trace and the result the safety
 * state reads both take it from here, so the two agree to the millisecond.
 */
internal fun receivedElapsedMs(frame: ImageFrame): Long? =
    frame.receivedElapsedRealtimeNanos.takeIf { it != 0L }?.let { it / 1_000_000 }

/**
 * Milliseconds from the source receiving [frame] to the decision at [decidedAtMs], both elapsed realtime.
 * Null when the source did not record the receipt (0, `ImageFrame.receivedElapsedRealtimeNanos`): not 0,
 * and never the camera timestamp, whose time base may be another clock.
 */
internal fun receiptToDecisionMs(frame: ImageFrame, decidedAtMs: Long?): Double? {
    val received = frame.receivedElapsedRealtimeNanos.takeIf { it != 0L } ?: return null
    return (decidedAtMs ?: return null) - received / 1e6
}
