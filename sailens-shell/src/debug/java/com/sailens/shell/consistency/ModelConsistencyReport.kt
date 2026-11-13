package com.sailens.shell.consistency

import android.os.Build
import com.sailens.runtime.hardware.DeviceHardwareProfileProvider
import com.sailens.shell.app.BuildIdentity
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.vision.consistency.ConsistencyThresholds
import com.sailens.vision.consistency.DepthConsistencyThresholds
import com.sailens.vision.consistency.DepthFrameAgreement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a consistency check found, as written to `files/diagnostics/consistency/`. Numbers only:
 * the camera frames are not kept, since they may show people. When the depth runs disagree on a
 * frame ([disagrees]), the two depth outputs of every frame are kept beside the report as float32
 * files ([ReportModel.depthOutputDirectory]): model outputs, not camera pixels, so the disagreement
 * can be refitted and located on a PC with the geometry the report records.
 */
@Serializable
internal data class ModelConsistencyReport(
    val type: String = "model_consistency",
    /** 2: the `depth` model and its thresholds. 3: per-frame depth detail and the kept depth outputs. */
    val schemaVersion: Int = 3,
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
    /** Per frame, what locates a disagreement: regions, fits, label changes, geometry, the kept output files. */
    val depthFrames: List<ReportDepthFrame>? = null,
    /**
     * Relative to the report's directory: where the two outputs of every frame were kept, because a frame disagreed.
     * Null when no frame disagreed, or when writing them failed ([depthOutputsError]).
     */
    val depthOutputDirectory: String? = null,
    val depthOutputsError: String? = null,
    /** "field=value" for every number above written as null because it was not finite, e.g. "frameMedianRelativeError[3]=Infinity". */
    val nonFiniteMetrics: List<String>? = null,
)

/** One depth frame. A number that is not finite is null, as in [ReportModel]. */
@Serializable
internal data class ReportDepthFrame(
    /** The two runs disagree on this frame by the check's own bands ([disagrees]). */
    val disagrees: Boolean,
    val p95RelativeError: Float?,
    /** Median relative error in each of [regionGrid] × [regionGrid] regions of the content area, row-major. */
    val regionGrid: Int,
    val regionMedianRelativeError: List<Float?>,
    val labelAgreement: Float?,
    /** Label-grid cells whose label differs, "reference->candidate" to how many; empty unless both fits are valid. */
    val labelChanges: Map<String, Int>,
    /** The CPU's fit and the accelerator's; null when the frame was not fitted. */
    val referenceFit: ReportFit?,
    val candidateFit: ReportFit?,
    /** What the frame was fitted with; null without intrinsics. */
    val geometry: ReportDepthGeometry?,
    val output: ReportDepthOutput,
)

@Serializable
internal data class ReportFit(
    val validity: String,
    val disparityScale: Float?,
    val disparityShift: Float?,
    val inlierRatio: Float?,
    val confidence: Float?,
    val planeAgreesWithGravity: Boolean,
    val groundCells: Int,
    val aboveGroundCells: Int,
    val belowGroundCells: Int,
)

/** The analysis image's intrinsics and the gravity direction in the camera frame, as the fits used them. */
@Serializable
internal data class ReportDepthGeometry(
    val width: Int,
    val height: Int,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val gravityX: Float?,
    val gravityY: Float?,
    val gravityZ: Float?,
    val gridWidth: Int,
    val gridHeight: Int,
)

/**
 * The output tensor and its input transform. [referenceFile] and [candidateFile], relative to the report's directory,
 * hold the outputs as little-endian float32, row-major [height] × [width]; null when they were not kept.
 */
@Serializable
internal data class ReportDepthOutput(
    val width: Int,
    val height: Int,
    val policy: String,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val referenceFile: String?,
    val candidateFile: String?,
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
        val keepOutputs = c.frames.any { it.disagrees(depthThresholds) } && r.details.any { it != null }
        val outputDirectory = if (keepOutputs) depthOutputDirectoryName(wallTimeMs) else null
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
            depthFrames = r.details.takeIf { it.any { d -> d != null } }?.let { details ->
                c.frames.mapIndexed { i, f -> reportDepthFrame(i, f, details.getOrNull(i), depthThresholds, outputDirectory, notFinite) }
            },
            depthOutputDirectory = outputDirectory,
            nonFiniteMetrics = notFinite.takeIf { it.isNotEmpty() },
        )
    } ?: ReportModel(status = depth.status.name, failure = depth.failure, modelSource = depth.modelSource),
)

internal fun depthOutputDirectoryName(wallTimeMs: Long): String = "consistency_${wallTimeMs}_depth"

private fun reportDepthFrame(
    index: Int,
    frame: DepthFrameAgreement,
    detail: DepthFrameDetail?,
    thresholds: DepthConsistencyThresholds,
    outputDirectory: String?,
    notFinite: MutableList<String>,
): ReportDepthFrame {
    val name = "depthFrames[$index]"
    val map = detail?.reference
    val geometry = detail?.geometry
    val intrinsics = geometry?.intrinsics
    return ReportDepthFrame(
        disagrees = frame.disagrees(thresholds),
        p95RelativeError = finiteOrNull("$name.p95RelativeError", frame.disparity.p95RelativeError, notFinite),
        regionGrid = DEPTH_REGION_GRID,
        regionMedianRelativeError = detail?.regionMedianRelativeError?.mapIndexed { j, e ->
            finiteOrNull("$name.regionMedianRelativeError[$j]", e, notFinite)
        }.orEmpty(),
        labelAgreement = finiteOrNull("$name.labelAgreement", frame.fit?.labelAgreement, notFinite),
        labelChanges = detail?.labelChanges.orEmpty(),
        referenceFit = detail?.referenceFit?.toReportFit("$name.referenceFit", notFinite),
        candidateFit = detail?.candidateFit?.toReportFit("$name.candidateFit", notFinite),
        geometry = intrinsics?.let { k ->
            val g = geometry.gravityInCamera
            ReportDepthGeometry(
                width = k.width,
                height = k.height,
                fx = k.fx,
                fy = k.fy,
                cx = k.cx,
                cy = k.cy,
                gravityX = g?.x,
                gravityY = g?.y,
                gravityZ = g?.z,
                gridWidth = FIT_GRID_WIDTH,
                gridHeight = FIT_GRID_HEIGHT,
            )
        },
        output = ReportDepthOutput(
            width = map?.width ?: 0,
            height = map?.height ?: 0,
            policy = map?.transform?.policy?.name ?: "",
            analysisWidth = map?.transform?.analysisWidth ?: 0,
            analysisHeight = map?.transform?.analysisHeight ?: 0,
            referenceFile = if (outputDirectory != null && detail != null) "$outputDirectory/frame${index}_reference.f32" else null,
            candidateFile = if (outputDirectory != null && detail != null) "$outputDirectory/frame${index}_candidate.f32" else null,
        ),
    )
}

private fun GroundObservation.toReportFit(name: String, notFinite: MutableList<String>) = ReportFit(
    validity = validity.name,
    disparityScale = finiteOrNull("$name.disparityScale", disparityScale, notFinite),
    disparityShift = finiteOrNull("$name.disparityShift", disparityShift, notFinite),
    inlierRatio = finiteOrNull("$name.inlierRatio", inlierRatio, notFinite),
    confidence = finiteOrNull("$name.confidence", confidence, notFinite),
    planeAgreesWithGravity = planeAgreesWithGravity,
    groundCells = groundMask.countTrue(),
    aboveGroundCells = aboveGroundMask.countTrue(),
    belowGroundCells = belowGroundMask.countTrue(),
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

    /**
     * Writes the depth outputs the report names ([depthDetails], in frame order), then the report. Outputs that
     * cannot be written are deleted and the report says so instead of naming them: a report never points at files
     * that are not there.
     *
     * @return the written report file.
     */
    fun write(report: ModelConsistencyReport, depthDetails: List<DepthFrameDetail?> = emptyList()): File {
        directory.mkdirs()
        val written = writeDepthOutputs(report, depthDetails)
        val file = File(directory, "consistency_${written.wallTimeMs}.json")
        val temp = File(directory, "${file.name}.tmp")
        temp.writeText(json.encodeToString(ModelConsistencyReport.serializer(), written))
        if (!temp.renameTo(file)) {
            temp.delete()
            error("could not write ${file.path}")
        }
        return file
    }

    private fun writeDepthOutputs(report: ModelConsistencyReport, details: List<DepthFrameDetail?>): ModelConsistencyReport {
        val outputDirectory = report.depth.depthOutputDirectory ?: return report
        val target = File(directory, outputDirectory)
        return try {
            target.mkdirs()
            report.depth.depthFrames.orEmpty().forEachIndexed { i, frame ->
                val detail = details.getOrNull(i) ?: return@forEachIndexed
                frame.output.referenceFile?.let { writeFloats(File(directory, it), detail.reference.values) }
                frame.output.candidateFile?.let { writeFloats(File(directory, it), detail.candidate.values) }
            }
            report
        } catch (error: IOException) {
            target.deleteRecursively()
            report.copy(
                depth = report.depth.copy(
                    depthOutputDirectory = null,
                    depthOutputsError = error.message ?: error.javaClass.simpleName,
                    depthFrames = report.depth.depthFrames?.map {
                        it.copy(output = it.output.copy(referenceFile = null, candidateFile = null))
                    },
                ),
            )
        }
    }

    private fun writeFloats(file: File, values: FloatArray) {
        val bytes = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(values)
        file.writeBytes(bytes.array())
    }
}
