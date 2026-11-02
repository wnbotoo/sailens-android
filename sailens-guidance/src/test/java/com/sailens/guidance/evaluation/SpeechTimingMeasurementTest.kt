package com.sailens.guidance.evaluation

import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.RegressionClocks
import com.sailens.guidance.trace.capture.RegressionUtteranceRecord
import com.sailens.guidance.trace.capture.UtteranceRecordEvents as E
import com.sailens.guidance.trace.capture.orThrow
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Two gaps between the speaker model's time and the phone's, measured on real captures; numbers only, and
 * skipped unless a path is given, as captures never enter the repository or CI:
 *
 * ```
 * gradlew :sailens-guidance:testDebugUnitTest --tests "*SpeechTimingMeasurementTest*" -PreplayCapture=<dir>
 * ```
 *
 * - **Decision to speech**: the model compares at the decision clock's reading, the phone on the main
 *   thread after the frame reached it. This is the time from the former to the offer's first lifecycle
 *   record.
 * - **Held past its end**: refusals as outranked at a moment when every accepted attempt already had its
 *   terminal record, so all that still held the speaker was a sentence whose main-thread task had not run.
 *   The time since the latest `done` is a lower bound on how late that task was.
 *
 * - **Decision to a flush**: from the decision of the frame that offered, to a sentence superseded on the speaker.
 *   The first lifecycle record is taken before the speak call, so it does not bound this one.
 *
 * Captures of the sealed acceptance set are skipped, as everywhere ([SealedCaptures]).
 */
class SpeechTimingMeasurementTest {

    @Test
    fun `speech timing gaps on recorded captures`() {
        val path = System.getProperty("sailens.replay.capture").orEmpty()
        assumeTrue("set -PreplayCapture=<capture dir> to measure on captures", path.isNotBlank())
        val out = File(System.getProperty("sailens.replay.out").orEmpty().ifBlank { "build/replay" }).apply { mkdirs() }
        val split = SealedCaptures.forMetrics(File(path))
        assumeTrue(SealedCaptures.refusalMessage(path, split.sealed.size), split.open.isNotEmpty())

        val hops = mutableListOf<Double>()
        val toFlush = mutableListOf<Double>()
        var offeredWithoutRecord = 0
        val heldPastEnd = mutableListOf<Double>()
        var outrankedRefusals = 0
        for (dir in split.open) {
            val session = CaptureSessionReader.read(dir).orThrow()
            val records = session.utterances.sortedBy { it.elapsedRealtimeNanos }
            val firstRecord = HashMap<String, RegressionUtteranceRecord>()
            for (r in records) {
                if (r.event != E.SUBMITTED && r.event != E.REFUSED) continue
                val id = r.logicalId ?: r.attemptId
                firstRecord.putIfAbsent(id, r)
            }
            for (frame in session.regressionFrames) {
                val offered = frame.events.firstOrNull()?.id ?: continue
                val decidedAt = frame.clocks[RegressionClocks.DECIDE]?.lastOrNull() ?: continue
                val first = firstRecord[offered]
                if (first == null) offeredWithoutRecord++ else hops += first.elapsedRealtimeNanos / 1e6 - decidedAt
            }
            val decisions = session.regressionFrames.filter { it.events.isNotEmpty() }.mapNotNull { it.clocks[RegressionClocks.DECIDE]?.lastOrNull() }.sorted()
            for (r in records) {
                if (r.event != E.SUPERSEDED) continue
                val now = r.elapsedRealtimeNanos / 1e6
                decisions.lastOrNull { it <= now }?.let { toFlush += now - it }
            }
            val open = HashSet<String>()
            var lastDoneNanos: Long? = null
            for (r in records) {
                when (r.event) {
                    E.SUBMITTED, E.REQUEUED -> open += r.attemptId
                    E.DONE -> {
                        open -= r.attemptId
                        lastDoneNanos = r.elapsedRealtimeNanos
                    }
                    E.STOPPED, E.ERROR, E.SUPERSEDED, E.CANCELLED, E.UNKNOWN_TERMINAL -> open -= r.attemptId
                    E.REFUSED -> if (r.reason == "outranked") {
                        outrankedRefusals++
                        val done = lastDoneNanos
                        if (open.isEmpty() && done != null) heldPastEnd += (r.elapsedRealtimeNanos - done) / 1e6
                    }
                }
            }
        }
        assertTrue("no offer measured under $path", hops.isNotEmpty())
        val report = buildString {
            appendLine("captures ${split.open.size}; sealed and skipped ${split.sealed.size}")
            appendLine("decision to first lifecycle record, ms: n ${hops.size}, ${quantiles(hops)}; beyond 150 ms ${hops.count { it > 150 }}; offers with no record $offeredWithoutRecord")
            if (toFlush.isNotEmpty()) appendLine("decision to a flush, ms: n ${toFlush.size}, ${quantiles(toFlush)}; beyond 50 ms ${toFlush.count { it > 50 }}")
            appendLine("decision to first record beyond 50 ms: ${hops.count { it > 50 }}")
            appendLine("refused as outranked $outrankedRefusals; of them with every accepted attempt already ended ${heldPastEnd.size}")
            if (heldPastEnd.isNotEmpty()) appendLine("  ms since the latest done: ${quantiles(heldPastEnd)}; beyond 150 ms ${heldPastEnd.count { it > 150 }}")
        }
        File(out, "speech-timing.txt").writeText(report)
        println(report)
    }

    private fun quantiles(values: List<Double>): String {
        val sorted = values.sorted()
        fun q(f: Double) = sorted[((sorted.size - 1) * f).toInt()]
        return String.format(Locale.ROOT, "min %.1f p50 %.1f p95 %.1f p99 %.1f max %.1f", sorted.first(), q(0.5), q(0.95), q(0.99), sorted.last())
    }
}
