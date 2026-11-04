package com.sailens.guidance.trace.regression

import com.sailens.guidance.trace.capture.RegressionStats

/**
 * What keeps a replay from being a replay of the whole capture, or null. A prefix is not the whole, so this asks the
 * manifest as well as the replay: a capture that lost a frame anywhere (even after the last one kept, which the replay
 * never reaches), a file with other than the frames the manifest counts, a mismatch, an approximate frame, a stop
 * before a lost frame, or fewer frames replayed than read.
 *
 * The one gate for every test that claims a capture replayed: an exact replay and a delivery fingerprint alike.
 *
 * @param readFrames the frames read from the capture's file.
 */
internal fun replayProblem(report: RegressionReplay.Report, readFrames: Int, stats: RegressionStats): String? = when {
    stats.framesLost > 0 || stats.firstLostSeq != null -> "the capture lost ${stats.framesLost} frame(s), the first at seq ${stats.firstLostSeq}"
    stats.framesRecorded != readFrames.toLong() -> "the manifest counts ${stats.framesRecorded} frames, the file has $readFrames"
    report.firstMismatch != null -> "first mismatch at seq ${report.firstMismatch.seq}"
    report.approximateFrames.isNotEmpty() -> "${report.approximateFrames.size} approximate frames"
    report.stoppedAtLostSeq != null -> "stopped before lost frame ${report.stoppedAtLostSeq}"
    report.framesReplayed != readFrames -> "replayed ${report.framesReplayed} of $readFrames frames"
    else -> null
}
