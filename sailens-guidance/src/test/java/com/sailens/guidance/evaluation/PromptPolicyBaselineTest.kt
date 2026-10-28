package com.sailens.guidance.evaluation

import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.orThrow
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The det-first plan's PR-0 baseline ([PromptPolicyCaptureEvaluation]) on real model-regression
 * captures. Skipped unless a path is given -- captures contain faces and places and never enter the
 * repository or CI:
 *
 * ```
 * gradlew :sailens-guidance:testDebugUnitTest --tests "*PromptPolicyBaselineTest*" -PreplayCapture=<dir> [-Pm3aPhoneHeight=1.15]
 * ```
 *
 * Reports go to `sailens-guidance/build/replay/<sessionId>.detfirst.txt` and a summary over all
 * complete captures to `detfirst-baseline.txt`; numbers only. The test fails on any replay problem
 * and unless at least one complete capture was evaluated.
 *
 * Captures of the sealed acceptance set ([SealedCaptures]) are skipped, and the summary says how
 * many, unless `-PunsealCapture` is given -- which the D1.8 plan allows only after the thresholds are
 * frozen.
 */
class PromptPolicyBaselineTest {

    @Test
    fun `det-first baseline on recorded captures`() {
        val path = System.getProperty("sailens.replay.capture").orEmpty()
        assumeTrue("set -PreplayCapture=<capture dir> to evaluate on captures", path.isNotBlank())
        val out = File(System.getProperty("sailens.replay.out").orEmpty().ifBlank { "build/replay" }).apply { mkdirs() }
        val height = System.getProperty("sailens.m3a.phoneHeight").orEmpty().ifBlank { "1.15" }.toFloat()
        val evaluation = PromptPolicyCaptureEvaluation(PhoneGeometrySettings(height, HeightSource.PRESET))
        val split = SealedCaptures.forMetrics(File(path))
        val sealed = split.sealed
        val captures = split.open
        assertTrue("no capture under $path", captures.isNotEmpty() || sealed.isNotEmpty())
        assumeTrue(SealedCaptures.refusalMessage(path, sealed.size), captures.isNotEmpty())

        val evaluated = captures.mapNotNull { dir -> evaluation.evaluate(CaptureSessionReader.read(dir).orThrow())?.let { dir to it } }
        val results = evaluated.map { it.second }
        val problems = results.flatMap { it.problems }
        val complete = results.filter { it.complete && it.evaluation != null }

        // A sentence's duration never comes from the capture being scored (threshold note §3.2): a
        // sealed capture takes the table frozen from C; a calibration capture takes the median over
        // the other calibration captures (leave one out), so C's numbers are as independent as S's.
        val samples = evaluated.filter { !SealedCaptures.isSealed(it.first) }.associate { (_, r) -> r.id to r.speaker?.measured.orEmpty() }
        fun durationsFor(dir: File, r: PromptPolicyCaptureEvaluation.Result) =
            if (SealedCaptures.isSealed(dir)) SpeakerDurations.FROZEN_C else SpeakerDurations.pooled(samples.filterKeys { it != r.id }.values.flatten(), "other captures")
        val calibrations = evaluated.associate { (dir, r) -> r.id to r.speaker?.calibrate(durationsFor(dir, r)) }
        val reports = results.associate { it.id to it.report(calibrations[it.id]) }
        results.filter { it.evaluable }.forEach { File(out, "${it.id}.detfirst.txt").writeText(reports.getValue(it.id)) }
        val table = SpeakerDurations.pooled(samples.values.flatten(), "c table").wordings.toSortedMap(compareBy({ it.first }, { it.second }))
        File(out, "speaker-durations-pooled.txt").writeText(table.entries.joinToString("\n") { (w, ms) -> "    entry(\"${w.first}\", ${w.second}, $ms)," } + "\n")

        val summary = buildString {
            appendLine(
                "det-first PR-0 baseline, phone height preset ${String.format(Locale.ROOT, "%.2f", height)} m " +
                    "(only for captures without recorded geometry; each capture says which height its distances use)",
            )
            appendLine("captures ${results.size}, complete and evaluated ${complete.size}; problems ${problems.size}; sealed and skipped ${sealed.size}")
            problems.forEach { appendLine("  $it") }
            appendLine()
            appendLine("ALL COMPLETE CAPTURES")
            append(PromptPolicyReport.render(complete.mapNotNull { it.evaluation }))
            // Pooled only over captures whose lifecycle was recorded in full, like the stage counts.
            val recorded = complete.filter { it.evaluation?.evidence == PromptPolicyEvaluation.LifecycleEvidence.RECORDED }
            recorded.mapNotNull { calibrations[it.id] }.reduceOrNull(SpeakerModel.Calibration::plus)
                ?.let { append(it.render("against the lifecycle, all complete captures with a recorded lifecycle; sealed: frozen C table, others: leave one out")) }
            recorded.mapNotNull { it.speaker?.calibrate(SpeakerDurations.D17_ONLY) }.reduceOrNull(SpeakerModel.Calibration::plus)
                ?.let { append(it.render("for reference: the D1.7 table alone")) }
            if (samples.isNotEmpty()) {
                val same = table == SpeakerDurations.FROZEN_C.wordings
                appendLine(
                    "  duration table pooled over the ${samples.size} unsealed captures: ${table.size} wordings; equals the frozen C table: ${if (same) "yes" else "NO"}; " +
                        "D1.7 fallback offset of the frozen table ${SpeakerDurations.FROZEN_C.d17OffsetMs} ms",
                )
            }
            results.forEach { appendLine().append(reports.getValue(it.id)) }
        }
        File(out, "detfirst-baseline.txt").writeText(summary)
        println(summary)
        assertTrue("evaluation problems: $problems", problems.isEmpty())
        assertTrue("no complete model_regression capture was evaluated under $path", complete.isNotEmpty())
    }
}
