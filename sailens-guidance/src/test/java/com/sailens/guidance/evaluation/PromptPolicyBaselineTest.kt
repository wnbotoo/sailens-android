package com.sailens.guidance.evaluation

import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.trace.capture.CaptureSchema
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
 */
class PromptPolicyBaselineTest {

    @Test
    fun `det-first baseline on recorded captures`() {
        val path = System.getProperty("sailens.replay.capture").orEmpty()
        assumeTrue("set -PreplayCapture=<capture dir> to evaluate on captures", path.isNotBlank())
        val out = File(System.getProperty("sailens.replay.out").orEmpty().ifBlank { "build/replay" }).apply { mkdirs() }
        val height = System.getProperty("sailens.m3a.phoneHeight").orEmpty().ifBlank { "1.15" }.toFloat()
        val evaluation = PromptPolicyCaptureEvaluation(PhoneGeometrySettings(height, HeightSource.PRESET))
        val captures = File(path).walkTopDown()
            .filter { it.isFile && it.name == CaptureSchema.MANIFEST_FILE }
            .mapNotNull { it.parentFile }
            .sortedBy { it.name }
            .toList()
        assertTrue("no capture under $path", captures.isNotEmpty())

        val results = captures.mapNotNull { evaluation.evaluate(CaptureSessionReader.read(it).orThrow()) }
        val problems = results.flatMap { it.problems }
        val complete = results.filter { it.complete && it.evaluation != null }
        results.filter { it.evaluable }.forEach { File(out, "${it.id}.detfirst.txt").writeText(it.text) }

        val summary = buildString {
            appendLine("det-first PR-0 baseline, phone height preset ${String.format(Locale.ROOT, "%.2f", height)} m")
            appendLine("captures ${results.size}, complete and evaluated ${complete.size}; problems ${problems.size}")
            problems.forEach { appendLine("  $it") }
            appendLine()
            appendLine("ALL COMPLETE CAPTURES")
            append(PromptPolicyReport.render(complete.mapNotNull { it.evaluation }))
            results.forEach { appendLine().append(it.text) }
        }
        File(out, "detfirst-baseline.txt").writeText(summary)
        println(summary)
        assertTrue("evaluation problems: $problems", problems.isEmpty())
        assertTrue("no complete model_regression capture was evaluated under $path", complete.isNotEmpty())
    }
}
