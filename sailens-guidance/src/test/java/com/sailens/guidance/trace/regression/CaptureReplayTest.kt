package com.sailens.guidance.trace.regression

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.semantics.DefaultNavigationSemanticsProvider
import com.sailens.guidance.trace.capture.CaptureModes
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.orThrow
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays real model-regression captures from disk (M0b exit criterion 1). Skipped unless a path is
 * given -- captures contain faces and places and never enter the repository or CI:
 *
 * ```
 * gradlew :sailens-guidance:testDebugUnitTest --tests "*CaptureReplayTest*" -PreplayCapture=<capture dir or a folder of them>
 * ```
 *
 * One report per capture goes to `sailens-guidance/build/replay/<sessionId>.txt`; the test fails
 * unless every `model_regression` capture under the path replays exactly.
 */
class CaptureReplayTest {

    @Test
    fun `recorded captures replay exactly`() {
        val path = System.getProperty("sailens.replay.capture").orEmpty()
        assumeTrue("set -PreplayCapture=<capture dir> to replay captures", path.isNotBlank())
        val out = File(System.getProperty("sailens.replay.out").orEmpty().ifBlank { "build/replay" }).apply { mkdirs() }
        val captures = File(path).walkTopDown()
            .filter { it.isFile && it.name == CaptureSchema.MANIFEST_FILE }
            .map { it.parentFile }
            .toList()
        assertTrue("no capture under $path", captures.isNotEmpty())

        val failures = mutableListOf<String>()
        var replayedAny = false
        for (directory in captures) {
            val session = CaptureSessionReader.read(directory).orThrow()
            val manifest = session.manifest
            if (manifest.captureMode != CaptureModes.MODEL_REGRESSION) continue
            replayedAny = true
            val block = checkNotNull(manifest.regression) { "${directory.name}: no regression block" }
            val semantics = DefaultNavigationSemanticsProvider().semanticSegmentationSemantics()
            check(block.taxonomyId == null || block.taxonomyId == semantics.taxonomyId.value) {
                "${directory.name}: recorded with taxonomy ${block.taxonomyId}, this build understands ${semantics.taxonomyId.value}"
            }
            val perception = block.perceptionConfig?.let { CaptureSchema.json.decodeFromJsonElement(PerceptionConfig.serializer(), it) }
                ?: error("${directory.name}: no perception config recorded")
            val analysis = block.analysisConfig?.let { CaptureSchema.json.decodeFromJsonElement(AnalysisConfig.serializer(), it) }
                ?: error("${directory.name}: no analysis config recorded")

            val report = RegressionReplay(perception, analysis, semantics).replay(
                frames = session.regressionFrames,
                revokes = session.revokes,
                classMap = { CaptureSessionReader.classMap(session, it) },
                firstLostSeq = block.stats.firstLostSeq,
            )
            val text = buildString {
                appendLine("capture ${manifest.sessionId} (${manifest.group ?: "no group"}: ${manifest.sceneLabel ?: "no scene"})")
                appendLine("build ${manifest.gitSha} models ${manifest.modelArtifacts}")
                appendLine("frames recorded ${session.regressionFrames.size}, replayed ${report.framesReplayed}, decisions ${report.decisions}")
                appendLine("exact ${report.exact}")
                report.stoppedAtLostSeq?.let { appendLine("stopped before lost frame $it (${block.stats.framesLost} lost)") }
                report.firstMismatch?.let { appendLine("first mismatch at seq ${it.seq}: ${it.what}\n  expected ${it.expected}\n  actual   ${it.actual}") }
                if (report.approximateFrames.isNotEmpty()) appendLine("approximate frames ${report.approximateFrames}")
                session.warnings.forEach { appendLine("warning: $it") }
            }
            File(out, "${manifest.sessionId}.txt").writeText(text)
            println(text)
            if (!report.exact) failures += manifest.sessionId
        }
        assertTrue("no model_regression capture under $path", replayedAny)
        assertTrue("not exact: $failures (reports in ${out.path})", failures.isEmpty())
    }
}
