package com.sailens.shell.consistency

import android.os.Build
import com.sailens.runtime.hardware.DeviceHardwareProfileProvider
import com.sailens.shell.app.BuildIdentity
import com.sailens.vision.consistency.ConsistencyThresholds
import com.sailens.vision.consistency.DepthConsistencyThresholds
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
    /** 2: the `depth` model and its thresholds. */
    val schemaVersion: Int = 2,
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
    /** PASS only when every applicable model was compared and passed (see `overallVerdict`). */
    val overall: String?,
    /** Models the profile runs on an accelerator. */
    val modelsApplicable: Int,
    /** Of those, how many were actually compared. */
    val modelsChecked: Int,
    val semantic: ReportModel,
    val detection: ReportModel,
    val depth: ReportModel = ReportModel(status = ModelCheckStatus.NOT_APPLICABLE.name),
)

@Serializable
internal data class ReportThresholds(
    val minMeanClassAgreement: Float,
    val minFrameClassAgreement: Float,
    val failBelowMeanClassAgreement: Float,
    val minDetectionRecall: Float,
    val maxExtraDetectionFraction: Float,
    val depthMaxPassMedianRelativeError: Float? = null,
    val depthFailAboveMedianRelativeError: Float? = null,
    val depthMinPassFitAgreeingFrames: Int? = null,
    val depthFailAtOrBelowFitAgreeingFrames: Int? = null,
    val depthMinLabelAgreement: Float? = null,
)

@Serializable
internal data class ReportModel(
    val status: String,
    val failure: String? = null,
    /** The one physical model both runs loaded. */
    val modelSource: String? = null,
    val candidateAccelerator: String? = null,
    val referenceAccelerator: String? = null,
    /** What the evidence supports (`effectiveVerdict`): never PASS on fewer frames than requested. */
    val verdict: String? = null,
    /** The comparison's own verdict over the frames that were compared. */
    val comparedVerdict: String? = null,
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
    // Depth. JSON has no Infinity or NaN: a number that is not finite is written as null, and [nonFiniteMetrics] says
    // which one it was and what it was, so the report of a failed check is still written whole.
    val frameMedianRelativeError: List<Float?>? = null,
    val maxMedianRelativeError: Float? = null,
    val framesWithGeometry: Int? = null,
    val fitAgreeingFrames: Int? = null,
    val minLabelAgreement: Float? = null,
    /** Per frame, NaN or infinite values in the accelerator's output; any is a failed run. */
    val frameCandidateNonFinite: List<Int>? = null,
    /** Per frame, NaN or infinite values in the CPU's output. */
    val frameReferenceNonFinite: List<Int>? = null,
    val nonFiniteOutputFrames: Int? = null,
    /** "field=value" for every number above written as null because it was not finite, e.g. "frameMedianRelativeError[3]=Infinity". */
    val nonFiniteMetrics: List<String>? = null,
)

/** [value] when finite; otherwise null, with "[name]=[value]" added to [notFinite]. */
private fun finiteOrNull(name: String, value: Float?, notFinite: MutableList<String>): Float? {
    if (value == null || value.isFinite()) return value
    notFinite += "$name=$value"
    return null
}

internal fun ConsistencyRunResult.toReport(
    wallTimeMs: Long,
    build: BuildIdentity?,
    semanticLabel: (Int) -> String,
    thresholds: ConsistencyThresholds = ConsistencyThresholds(),
    depthThresholds: DepthConsistencyThresholds = DepthConsistencyThresholds(framesRequired = framesRequested),
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
        depthMaxPassMedianRelativeError = depthThresholds.maxPassMedianRelativeError,
        depthFailAboveMedianRelativeError = depthThresholds.failAboveMedianRelativeError,
        depthMinPassFitAgreeingFrames = depthThresholds.minPassFitAgreeingFrames,
        depthFailAtOrBelowFitAgreeingFrames = depthThresholds.failAtOrBelowFitAgreeingFrames,
        depthMinLabelAgreement = depthThresholds.minLabelAgreement,
    ),
    overall = overall?.name,
    modelsApplicable = modelsApplicable,
    modelsChecked = modelsChecked,
    semantic = semantic.result?.let { r ->
        val c = r.consistency
        ReportModel(
            status = semantic.status.name,
            failure = semantic.failure,
            modelSource = semantic.modelSource,
            candidateAccelerator = r.accelerators.candidate,
            referenceAccelerator = r.accelerators.reference,
            verdict = semanticVerdict?.name,
            comparedVerdict = c.verdict.name,
            meanClassAgreement = c.meanAgreement,
            minClassAgreement = c.minAgreement,
            frameClassAgreement = c.frames.map { it.agreement },
            topConfusions = pooledConfusions(c.frames).map { (pair, fraction) ->
                "${semanticLabel(pair.first)}->${semanticLabel(pair.second)} ${"%.3f".format(fraction)}"
            },
        )
    } ?: ReportModel(status = semantic.status.name, failure = semantic.failure, modelSource = semantic.modelSource),
    detection = detection.result?.let { r ->
        val c = r.consistency
        ReportModel(
            status = detection.status.name,
            failure = detection.failure,
            modelSource = detection.modelSource,
            candidateAccelerator = r.accelerators.candidate,
            referenceAccelerator = r.accelerators.reference,
            verdict = detectionVerdict?.name,
            comparedVerdict = c.verdict.name,
            recall = c.recall,
            extraFraction = c.extraFraction,
            referenceDetections = c.frames.sumOf { it.referenceCount },
            candidateDetections = c.frames.sumOf { it.candidateCount },
        )
    } ?: ReportModel(status = detection.status.name, failure = detection.failure, modelSource = detection.modelSource),
    depth = depth.result?.let { r ->
        val c = r.consistency
        val notFinite = mutableListOf<String>()
        ReportModel(
            status = depth.status.name,
            failure = depth.failure,
            modelSource = depth.modelSource,
            candidateAccelerator = r.accelerators.candidate,
            referenceAccelerator = r.accelerators.reference,
            verdict = depthVerdict?.name,
            comparedVerdict = c.verdict.name,
            frameMedianRelativeError = c.frames.mapIndexed { i, f ->
                finiteOrNull("frameMedianRelativeError[$i]", f.disparity.medianRelativeError, notFinite)
            },
            maxMedianRelativeError = finiteOrNull("maxMedianRelativeError", c.maxMedianRelativeError, notFinite),
            framesWithGeometry = c.framesWithGeometry,
            fitAgreeingFrames = c.fitAgreeingFrames,
            minLabelAgreement = finiteOrNull("minLabelAgreement", c.minLabelAgreement, notFinite),
            frameCandidateNonFinite = c.frames.map { it.candidateNonFinite },
            frameReferenceNonFinite = c.frames.map { it.referenceNonFinite },
            nonFiniteOutputFrames = c.nonFiniteOutputFrames,
            nonFiniteMetrics = notFinite.takeIf { it.isNotEmpty() },
        )
    } ?: ReportModel(status = depth.status.name, failure = depth.failure, modelSource = depth.modelSource),
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
