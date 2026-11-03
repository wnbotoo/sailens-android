package com.sailens.guidance.delivery.fingerprint

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.evaluation.BirthOrderTrackIds
import com.sailens.guidance.evaluation.SealedCaptures
import com.sailens.guidance.semantics.DefaultNavigationSemanticsProvider
import com.sailens.guidance.trace.capture.CaptureModes
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.orThrow
import com.sailens.guidance.trace.regression.RegressionReplay
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The delivery fingerprint of real model-regression captures: for each capture, with the switches off and with
 * [DetFirstConfig.LAUNCH], the real path up to the decision runs once, and every frame goes on to one
 * [FingerprintPipeline] per [OutputCondition]. Skipped unless a path is given -- captures contain faces and places and
 * never enter the repository or CI:
 *
 * ```
 * gradlew :sailens-guidance:testDebugUnitTest --tests "*DeliveryFingerprintTest*" -PreplayCapture=<capture dir or a folder of them> [-PfingerprintBaseline=<dir>]
 * ```
 *
 * Fingerprints go to `sailens-guidance/build/fingerprint/<sessionId>.<off|launch>.<v1..v5>.txt` and stay on this
 * machine. With a baseline directory (a copy of an earlier run's, never the output directory itself), each is compared
 * with the file of the same name there, and the test fails at the first difference, naming only the capture, the frame
 * and the kind of line. A capture whose path up to the decision does not replay whole and exact fails too
 * ([replayProblem]). A sealed capture is skipped ([SealedCaptures]).
 */
class DeliveryFingerprintTest {

    @Test
    fun `recorded captures write their delivery fingerprints`() {
        val path = System.getProperty("sailens.replay.capture").orEmpty()
        assumeTrue("set -PreplayCapture=<capture dir> to fingerprint captures", path.isNotBlank())
        val split = SealedCaptures.forMetrics(File(path))
        assumeTrue(SealedCaptures.refusalMessage(path, split.sealed.size), split.open.isNotEmpty())
        val out = File(System.getProperty("sailens.fingerprint.out").orEmpty().ifBlank { "build/fingerprint" })
        val baseline = System.getProperty("sailens.fingerprint.baseline").orEmpty().takeIf { it.isNotBlank() }?.let(::File)
        val files = FingerprintFiles(out, baseline)

        val failures = mutableListOf<String>()
        var captures = 0
        var frames = 0L
        for (directory in split.open) {
            val session = CaptureSessionReader.read(directory).orThrow()
            val manifest = session.manifest
            if (manifest.captureMode != CaptureModes.MODEL_REGRESSION) continue
            captures++
            val started = System.nanoTime()
            val block = checkNotNull(manifest.regression) { "${directory.name}: no regression block" }
            val semantics = DefaultNavigationSemanticsProvider().semanticSegmentationSemantics()
            check(block.taxonomyId == null || block.taxonomyId == semantics.taxonomyId.value) {
                "${directory.name}: recorded with taxonomy ${block.taxonomyId}, this build understands ${semantics.taxonomyId.value}"
            }
            val perception = block.perceptionConfig?.let { CaptureSchema.json.decodeFromJsonElement(PerceptionConfig.serializer(), it) }
                ?: error("${directory.name}: no perception config recorded")
            val recorded = block.analysisConfig?.let { CaptureSchema.json.decodeFromJsonElement(AnalysisConfig.serializer(), it) }
                ?: error("${directory.name}: no analysis config recorded")
            val outage = outageOf(session.regressionFrames.map { it.seq })

            for ((label, detFirst) in CONFIGS) {
                val analysis = recorded.copy(detFirst = detFirst)
                val pipelines = OutputCondition.entries.associateWith { FingerprintPipeline(analysis, it, inOutage = { seq -> seq in outage }) }
                val report = RegressionReplay(perception, analysis, semantics).replayScenes(
                    frames = session.regressionFrames,
                    classMap = { CaptureSessionReader.classMap(session, it) },
                    firstLostSeq = block.stats.firstLostSeq,
                    trackIds = BirthOrderTrackIds(),
                ) { scene ->
                    val frame = FingerprintFrame.of(scene)
                    for (pipeline in pipelines.values) pipeline.onFrame(frame)
                }
                replayProblem(report, session.regressionFrames.size, block.stats)?.let { problem ->
                    failures += "${manifest.sessionId} $label: the path up to the decision did not replay the whole capture exactly: $problem"
                }
                if (label == CONFIGS.first().first) frames += report.framesReplayed
                for ((condition, pipeline) in pipelines) {
                    pipeline.finish()
                    val name = "${manifest.sessionId}.$label.${condition.name.lowercase()}.txt"
                    files.record(name, pipeline.lines)?.let { failures += "${manifest.sessionId} $label ${condition.name}: $it" }
                }
            }
            println("${manifest.sessionId}: ${session.regressionFrames.size} frames, ${(System.nanoTime() - started) / 1_000_000} ms")
        }
        println("fingerprinted $captures capture(s), $frames frames replayed, into ${out.path}" + (baseline?.let { "; compared with ${it.path}" } ?: ""))
        assertTrue("no model_regression capture under $path", captures > 0)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private companion object {
        val CONFIGS = listOf("off" to DetFirstConfig(), "launch" to DetFirstConfig.LAUNCH)

        /** The engine outage of [OutputCondition.V4] and [OutputCondition.V5]: the middle third of a capture's frames. */
        fun outageOf(seqs: List<Long>): LongRange {
            if (seqs.isEmpty()) return LongRange.EMPTY
            val sorted = seqs.sorted()
            return sorted[sorted.size / 3]..sorted[(2 * sorted.size) / 3]
        }
    }
}
