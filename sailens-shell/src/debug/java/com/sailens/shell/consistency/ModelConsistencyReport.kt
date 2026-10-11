package com.sailens.shell.consistency

import android.os.Build
import com.sailens.runtime.hardware.DeviceHardwareProfileProvider
import com.sailens.shell.app.BuildIdentity
import com.sailens.vision.consistency.ConsistencyThresholds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What a consistency check found, as written to `files/diagnostics/consistency/`. Numbers only:
 * the camera frames are not kept, since they may show people.
 */
@Serializable
internal data class ModelConsistencyReport(
    val type: String = "model_consistency",
    val schemaVersion: Int = 1,
    val wallTimeMs: Long,
    val device: String,
    val hardwareProfile: String,
    val sdkInt: Int,
    val gitSha: String?,
    /** Packaged model file -> "sha256:<hex>", from the build provenance. */
    val modelArtifacts: Map<String, String>,
    val framesRequested: Int,
    val framesUsed: Int,
    val framesChanged: Int,
    val thresholds: ReportThresholds,
    val overall: String?,
    val semantic: ReportModel,
    val detection: ReportModel,
)

@Serializable
internal data class ReportThresholds(
    val minMeanClassAgreement: Float,
    val minFrameClassAgreement: Float,
    val failBelowMeanClassAgreement: Float,
    val minDetectionRecall: Float,
    val maxExtraDetectionFraction: Float,
)

@Serializable
internal data class ReportModel(
    val status: String,
    val failure: String? = null,
    val candidateAccelerator: String? = null,
    val referenceAccelerator: String? = null,
    val verdict: String? = null,
    // Semantic
    val meanClassAgreement: Float? = null,
    val minClassAgreement: Float? = null,
    val frameClassAgreement: List<Float>? = null,
    /** "reference->candidate fraction" over all frames, largest first. */
    val topConfusions: List<String>? = null,
    // Detection
    val recall: Float? = null,
    val extraFraction: Float? = null,
    val referenceDetections: Int? = null,
    val candidateDetections: Int? = null,
)

internal fun ConsistencyRunResult.toReport(
    wallTimeMs: Long,
    build: BuildIdentity?,
    semanticLabel: (Int) -> String,
    thresholds: ConsistencyThresholds = ConsistencyThresholds(),
): ModelConsistencyReport = ModelConsistencyReport(
    wallTimeMs = wallTimeMs,
    device = "${Build.MANUFACTURER} ${Build.MODEL}",
    hardwareProfile = DeviceHardwareProfileProvider.detect(),
    sdkInt = Build.VERSION.SDK_INT,
    gitSha = build?.gitSha,
    modelArtifacts = build?.modelArtifacts.orEmpty(),
    framesRequested = framesRequested,
    framesUsed = framesUsed,
    framesChanged = framesChanged,
    thresholds = ReportThresholds(
        minMeanClassAgreement = thresholds.minMeanClassAgreement,
        minFrameClassAgreement = thresholds.minFrameClassAgreement,
        failBelowMeanClassAgreement = thresholds.failBelowMeanClassAgreement,
        minDetectionRecall = thresholds.minDetectionRecall,
        maxExtraDetectionFraction = thresholds.maxExtraDetectionFraction,
    ),
    overall = overall?.name,
    semantic = semantic.result?.let { r ->
        val c = r.consistency
        ReportModel(
            status = semantic.status.name,
            candidateAccelerator = r.accelerators.candidate,
            referenceAccelerator = r.accelerators.reference,
            verdict = c.verdict.name,
            meanClassAgreement = c.meanAgreement,
            minClassAgreement = c.minAgreement,
            frameClassAgreement = c.frames.map { it.agreement },
            topConfusions = pooledConfusions(c.frames).map { (pair, fraction) ->
                "${semanticLabel(pair.first)}->${semanticLabel(pair.second)} ${"%.3f".format(fraction)}"
            },
        )
    } ?: ReportModel(status = semantic.status.name, failure = semantic.failure),
    detection = detection.result?.let { r ->
        val c = r.consistency
        ReportModel(
            status = detection.status.name,
            candidateAccelerator = r.accelerators.candidate,
            referenceAccelerator = r.accelerators.reference,
            verdict = c.verdict.name,
            recall = c.recall,
            extraFraction = c.extraFraction,
            referenceDetections = c.frames.sumOf { it.referenceCount },
            candidateDetections = c.frames.sumOf { it.candidateCount },
        )
    } ?: ReportModel(status = detection.status.name, failure = detection.failure),
)

/** The per-frame confusions averaged over all frames, largest first. */
internal fun pooledConfusions(
    frames: List<com.sailens.vision.consistency.ClassMapAgreement>,
    top: Int = 3,
): List<Pair<Pair<Int, Int>, Float>> {
    if (frames.isEmpty()) return emptyList()
    val sums = HashMap<Pair<Int, Int>, Float>()
    frames.forEach { f ->
        f.topConfusions.forEach { c ->
            val key = c.referenceClassId to c.candidateClassId
            sums[key] = (sums[key] ?: 0f) + c.fraction
        }
    }
    return sums.entries
        .map { it.key to it.value / frames.size }
        .sortedByDescending { it.second }
        .take(top)
}

internal class ModelConsistencyReportWriter(private val directory: File) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** @return the written file. */
    fun write(report: ModelConsistencyReport): File {
        directory.mkdirs()
        val file = File(directory, "consistency_${report.wallTimeMs}.json")
        val temp = File(directory, "${file.name}.tmp")
        temp.writeText(json.encodeToString(ModelConsistencyReport.serializer(), report))
        if (!temp.renameTo(file)) {
            temp.delete()
            error("could not write ${file.path}")
        }
        return file
    }
}
