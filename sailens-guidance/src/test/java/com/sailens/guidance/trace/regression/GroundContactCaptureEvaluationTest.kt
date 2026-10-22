package com.sailens.guidance.trace.regression

import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.evaluation.SealedCaptures
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.orThrow
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * M3a's offline evaluation ([GroundContactEvaluation]) on real model-regression captures. Skipped
 * unless a path is given -- captures contain faces and places and never enter the repository or CI:
 *
 * ```
 * gradlew :sailens-guidance:testDebugUnitTest --tests "*GroundContactCaptureEvaluationTest*" -PreplayCapture=<dir> [-Pm3aPhoneHeight=1.15]
 * ```
 *
 * Reports go to `sailens-guidance/build/replay/<sessionId>.m3a.txt` and a summary to
 * `m3a-summary.txt`; they hold numbers only. The test fails on any replay problem, and unless at
 * least one complete capture was evaluated.
 *
 * Like every metric on local captures, it skips the det-first D1.8 sealed set ([SealedCaptures]).
 */
class GroundContactCaptureEvaluationTest {

    @Test
    fun `ground contact evaluation on recorded captures`() {
        val path = System.getProperty("sailens.replay.capture").orEmpty()
        assumeTrue("set -PreplayCapture=<capture dir> to evaluate on captures", path.isNotBlank())
        val out = File(System.getProperty("sailens.replay.out").orEmpty().ifBlank { "build/replay" }).apply { mkdirs() }
        val height = System.getProperty("sailens.m3a.phoneHeight").orEmpty().ifBlank { "1.3" }.toFloat()
        val evaluation = GroundContactEvaluation(PhoneGeometrySettings(height, HeightSource.PRESET))
        val split = SealedCaptures.forMetrics(File(path))
        val captures = split.open
        assertTrue("no capture under $path", captures.isNotEmpty() || split.sealed.isNotEmpty())
        assumeTrue(SealedCaptures.refusalMessage(path, split.sealed.size), captures.isNotEmpty())

        val summary = StringBuilder("M3a evaluation, phone height preset ${String.format(Locale.ROOT, "%.2f", height)} m\n")
        if (split.sealed.isNotEmpty()) summary.appendLine("sealed and skipped: ${split.sealed.size}")
        val problems = mutableListOf<String>()
        var complete = 0
        for (directory in captures) {
            val result = evaluation.evaluate(CaptureSessionReader.read(directory).orThrow()) ?: continue
            problems += result.problems
            if (result.complete) complete++
            if (result.evaluable) File(out, "${result.id}.m3a.txt").writeText(result.text)
            summary.appendLine().append(result.text)
        }
        summary.appendLine().appendLine("complete captures evaluated: $complete; problems: ${problems.size}")
        problems.forEach { summary.appendLine("  $it") }
        File(out, "m3a-summary.txt").writeText(summary.toString())
        println(summary)
        assertTrue("evaluation problems: $problems", problems.isEmpty())
        assertTrue("no complete model_regression capture was evaluated under $path", complete > 0)
    }
}
