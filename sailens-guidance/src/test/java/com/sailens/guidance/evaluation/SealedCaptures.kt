package com.sailens.guidance.evaluation

import com.sailens.guidance.trace.capture.CaptureSchema
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The sealed acceptance set of det-first D1.8 must not be looked at before the thresholds are frozen
 * (docs/experiments/detfirst-d18-recording-plan-2026-10.md). A capture is sealed when a file named
 * [MARKER] sits in its directory or any directory above it -- above the path as given (normalized:
 * `a/../b` is `b`) **or** above its real location (links and junctions resolved). Either one seals:
 * reaching a sealed capture through an alias must not unseal it, and refusing too much is the safe
 * side.
 *
 * Every entry that computes metrics on local captures goes through [forMetrics]; integrity checks
 * (exact replay, `CaptureReplayTest`) do not need to.
 */
internal object SealedCaptures {
    const val MARKER: String = "SEALED"

    /** Set by `-PunsealCapture`; the D1.8 plan allows it only once the thresholds are frozen. */
    const val UNSEAL_PROPERTY: String = "sailens.replay.unseal"

    fun isSealed(captureDirectory: File): Boolean {
        val given = captureDirectory.toPath().toAbsolutePath().normalize()
        val real = runCatching { captureDirectory.toPath().toRealPath() }.getOrNull()
        return listOfNotNull(given, real).any(::markedAbove)
    }

    private fun markedAbove(path: Path): Boolean =
        generateSequence(path) { it.parent }.any { Files.isRegularFile(it.resolve(MARKER)) }

    /** The captures under [root] a metric may read, and the sealed ones it must skip. */
    class ForMetrics(val open: List<File>, val sealed: List<File>)

    /** Every capture directory under [root] (one with a manifest), split by [isSealed] unless [unseal]. */
    fun forMetrics(root: File, unseal: Boolean = System.getProperty(UNSEAL_PROPERTY) == "true"): ForMetrics {
        val found = root.walkTopDown()
            .filter { it.isFile && it.name == CaptureSchema.MANIFEST_FILE }
            .mapNotNull { it.parentFile }
            .sortedBy { it.name }
            .toList()
        val (sealed, open) = found.partition { !unseal && isSealed(it) }
        return ForMetrics(open, sealed)
    }

    fun refusalMessage(root: String, sealed: Int): String =
        "all $sealed capture(s) under $root are sealed (det-first D1.8); pass -PunsealCapture only after the thresholds are frozen"
}
