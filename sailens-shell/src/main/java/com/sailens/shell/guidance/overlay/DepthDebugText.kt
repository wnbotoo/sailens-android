package com.sailens.shell.guidance.overlay

import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.depth.geometry.DepthGeometrySnapshot
import com.sailens.guidance.safety.CapabilityStatus
import com.sailens.guidance.safety.traceIssues
import com.sailens.guidance.safety.traceStatus

/**
 * The debug panel's depth line: the `DEPTH_GEOMETRY` capability for this frame, then the latest run -- its number,
 * its age by frame time, the fit's validity and labels, its timings and the model's shape. An engineering readout in
 * trace terms, like the rest of the panel.
 */
internal fun depthDebugText(snapshot: DepthGeometrySnapshot?, status: CapabilityStatus, ageMs: Long?): String = buildString {
    append(status.traceStatus)
    status.traceIssues.takeIf { it.isNotEmpty() }?.let { append(" (").append(it.joinToString(", ")).append(')') }
    val observation = snapshot?.observation ?: return@buildString
    append(" · run ").append(snapshot.runId)
    append(" · age ").append(ageMs?.let { "$it ms" } ?: "-")
    append(" · ").append(observation.validity.name.lowercase())
    if (observation.isValid) {
        append(" ground ").append(percent(observation.groundMask))
        append(" above ").append(percent(observation.aboveGroundMask))
        append(" below ").append(percent(observation.belowGroundMask))
    }
    // Metric depth: the phone height it measures, against the setting.
    snapshot.phoneHeight?.let { check ->
        append(" · height ").append("%.2f".format(java.util.Locale.ROOT, check.measuredM)).append(" m (setting ")
        append("%+.0f".format(java.util.Locale.ROOT, check.relativeDifference * 100)).append("%)")
    }
    append(" · prepare/infer/fit ")
    append(listOf(snapshot.prepareMs, snapshot.inferMs, snapshot.fitMs).joinToString("/") { it?.toString() ?: "-" })
    append(" ms")
    snapshot.modelShape?.let { append(" · ").append(it) }
}

private fun percent(mask: BinaryMask): String {
    val total = mask.width * mask.height
    return if (total == 0) "-" else "${mask.countTrue() * 100 / total}%"
}
