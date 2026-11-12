package com.sailens.guidance.trace.regression

import com.sailens.guidance.evaluation.SealedCaptures
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.DepthEvidenceData
import com.sailens.guidance.trace.capture.DepthEvidenceRecord
import com.sailens.guidance.trace.capture.orThrow
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays the depth evidence stream of real captures (docs/field-capture.md §11). Skipped unless a path is
 * given -- captures contain faces and places and never enter the repository or CI:
 *
 * ```
 * gradlew :sailens-guidance:testDebugUnitTest --tests "*DepthEvidenceCaptureReplayTest*" -PreplayDepth=<capture or folder> [-PdepthPcOutputs=<dir>]
 * ```
 *
 * `-PdepthPcOutputs` is the `--out` folder of `scripts/capture/depth_evidence.py`, which reran the same runs on
 * a PC. One report per capture goes to `sailens-guidance/build/replay/<sessionId>.depth.txt`. Without PC outputs
 * the test checks the exact replay only and fails when a replayed fit differs from the phone's. With them it is the
 * acceptance: every depth session must pass, and one that cannot be judged (too few acceptable runs, PC outputs
 * missing) fails it. Sealed captures are skipped unless `-PunsealCapture`.
 */
class DepthEvidenceCaptureReplayTest {

    @Test
    fun `recorded depth runs replay, and agree with the PC within the bands`() {
        val path = System.getProperty("sailens.replay.depth").orEmpty()
        assumeTrue("set -PreplayDepth=<capture dir> to replay depth evidence", path.isNotBlank())
        val pcRoot = System.getProperty("sailens.replay.depthPc").orEmpty().takeIf { it.isNotBlank() }?.let(::File)
        val out = File(System.getProperty("sailens.replay.out").orEmpty().ifBlank { "build/replay" }).apply { mkdirs() }
        val found = SealedCaptures.forMetrics(File(path))
        found.sealed.forEach { println("skipped sealed capture ${it.path}") }

        val failures = mutableListOf<String>()
        val undecided = mutableListOf<String>()
        var replayedAny = false
        for (directory in found.open) {
            val session = CaptureSessionReader.read(directory).orThrow()
            if (session.depthEvidence.isEmpty()) continue
            replayedAny = true
            val manifest = session.manifest
            val pcDir = pcRoot?.let { File(it, manifest.sessionId) }
            val candidate: ((DepthEvidenceRecord) -> FloatArray?)? = pcDir?.let { dir ->
                { record ->
                    File(dir, "g${record.generation}_r${record.runId}.f32").takeIf { it.isFile }?.let { file ->
                        DepthEvidenceData.floats(file.readBytes(), record.output.width * record.output.height)
                    }
                }
            }
            val stats = manifest.depthEvidence?.stats
            val reports = DepthEvidenceReplay().replay(
                records = session.depthEvidence,
                output = { CaptureSessionReader.depthOutput(session, it) },
                candidate = candidate,
                firstDropped = stats?.firstDroppedRunId?.let { runId ->
                    DepthEvidenceReplay.DroppedRun(checkNotNull(stats.firstDroppedGeneration), runId)
                },
            )
            val text = buildString {
                appendLine("capture ${manifest.sessionId} (${manifest.group ?: "no group"}: ${manifest.sceneLabel ?: "no scene"})")
                appendLine("build ${manifest.gitSha} models ${manifest.modelArtifacts}")
                manifest.depthEvidence?.stats?.let { appendLine("stream $it") }
                appendLine("pc ${pcDir?.path ?: "none"}")
                for (r in reports) {
                    appendLine("depth session ${r.generation}: ${r.verdict} -- ${r.verdictReason}")
                    appendLine("  records ${r.records}, fitted ${r.fittedRecords}, non-finite ${r.nonFiniteRecords}, acceptable ${r.acceptableRuns}")
                    r.firstGap?.let { appendLine("  acceptance ends: $it") }
                    appendLine("  exact ${r.exactRuns}, mismatches ${r.mismatchCount}, max scale diff ${r.maxScaleDifference}, max shift diff ${r.maxShiftDifference}")
                    r.mismatches.forEach { appendLine("  mismatch run ${it.runId} (fit ${it.fitOrdinal}): ${it.what}, expected ${it.expected}, actual ${it.actual}") }
                    r.pc?.let { pc ->
                        appendLine(
                            "  pc runs ${pc.runs}: relative error median ${pc.medianRelativeError} p95 ${pc.p95RelativeError} max ${pc.maxRelativeError}, " +
                                "validity agreement ${pc.validityAgreement}, label agreement ${pc.labelAgreement} over ${pc.labelRuns} " +
                                "(each run fitted on its own); in session order, validity agreement ${pc.sessionValidityAgreement} (not judged)",
                        )
                        pc.stoppedAt?.let { appendLine("  pc stopped: $it") }
                    }
                }
                session.warnings.forEach { appendLine("warning: $it") }
            }
            File(out, "${manifest.sessionId}.depth.txt").writeText(text)
            println(text)
            for (r in reports) {
                when (r.verdict) {
                    DepthEvidenceVerdict.FAIL -> failures += "${manifest.sessionId}/${r.generation}: ${r.verdictReason}"
                    DepthEvidenceVerdict.CANNOT_JUDGE -> undecided += "${manifest.sessionId}/${r.generation}: ${r.verdictReason}"
                    DepthEvidenceVerdict.PASS -> Unit
                }
            }
        }
        assertTrue("no capture with depth evidence under $path", replayedAny)
        assertTrue("depth evidence failed: $failures (reports in ${out.path})", failures.isEmpty())
        undecided.forEach { println("cannot judge: $it") }
        // Comparing with a PC is the acceptance: a capture that cannot be judged has not passed it.
        if (pcRoot != null) assertTrue("cannot be judged, so not passed: $undecided (reports in ${out.path})", undecided.isEmpty())
    }
}
