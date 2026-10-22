package com.sailens.guidance.evaluation

import java.io.File

/**
 * The sealed acceptance set of det-first D1.8 must not be looked at before the thresholds are frozen
 * (docs/experiments/detfirst-d18-recording-plan-2026-10.md). A capture is sealed when its directory,
 * or any directory above it, holds a file named [MARKER]. Metrics refuse sealed captures unless
 * explicitly unsealed; integrity checks such as exact replay do not need this.
 */
internal object SealedCaptures {
    const val MARKER: String = "SEALED"

    fun isSealed(captureDirectory: File): Boolean =
        generateSequence(captureDirectory.absoluteFile) { it.parentFile }.any { File(it, MARKER).isFile }
}
