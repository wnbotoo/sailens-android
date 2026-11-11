package com.sailens.shell.consistency

import android.content.Context
import com.sailens.camera.FrameSnapshotProvider
import com.sailens.core.frame.ImageFrame
import com.sailens.core.log.LogService
import com.sailens.runtime.ModelAcceleratorBackend
import com.sailens.runtime.ModelAcceleratorSelectionMode
import com.sailens.runtime.ModelSourceResolver
import com.sailens.runtime.ModelType
import com.sailens.runtime.PinnedModelSourceResolver
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.vision.consistency.ClassMapAgreement
import com.sailens.vision.consistency.ConsistencyVerdict
import com.sailens.vision.consistency.DEFAULT_CONFIDENCE_MARGIN
import com.sailens.vision.consistency.DepthConsistency
import com.sailens.vision.consistency.DepthConsistencyThresholds
import com.sailens.vision.consistency.DepthFrameAgreement
import com.sailens.vision.consistency.DetectionAgreement
import com.sailens.vision.consistency.DetectionConsistency
import com.sailens.vision.consistency.GroundFitAgreement
import com.sailens.vision.consistency.SemanticConsistency
import com.sailens.vision.consistency.compareClassMaps
import com.sailens.vision.consistency.compareDetections
import com.sailens.vision.consistency.compareDisparity
import com.sailens.vision.consistency.judgeDepth
import com.sailens.vision.consistency.judgeDetection
import com.sailens.vision.consistency.judgeSemantic
import com.sailens.vision.depth.DepthModelConfig
import com.sailens.vision.depth.DepthModelPreflight
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.LiteRtDepthRunner
import com.sailens.vision.detection.DetectionModelConfig
import com.sailens.vision.detection.LiteRtDetectionRunner
import com.sailens.vision.semantic.ClassMapPostprocessor
import com.sailens.vision.semantic.LiteRtSemanticSegmenter
import com.sailens.vision.semantic.SemanticModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.zip.CRC32

/**
 * Runs each model twice on the same camera frames -- once on the accelerator the runtime profile
 * selects, once on the CPU -- and compares the two answers (`AcceleratorConsistency`).
 *
 * The two runs of a model differ only in the accelerator: the same physical model file (pinned
 * with [PinnedModelSourceResolver], never left to a resolver that may pick per accelerator), the
 * same frame, no shared preprocessing cache. The CPU answer is the reference. A model the profile
 * already runs on the CPU has nothing to compare and is reported as such.
 *
 * Results do not depend on GPU contention, only timings do; Guidance is still kept stopped by the
 * caller so the check never competes with live guidance for the camera or the GPU.
 *
 * Evidence invariant: a model passes only when all [frameCount] snapshots were compared. The
 * thresholds and the device evidence behind them are for 8 frames; one frame that happened to
 * agree proves nothing. A camera timeout or a snapshot that changed under the comparison leaves
 * the model [ModelCheckStatus.INCOMPLETE] -- still a failure if the frames that were compared
 * already fail, never a pass.
 */
internal class ModelConsistencyChecker(
    private val semanticPairs: ComparisonPairFactory<ClassMapAgreement>,
    private val detectionPairs: ComparisonPairFactory<DetectionAgreement>,
    private val snapshots: FrameSnapshotProvider,
    private val log: LogService,
    private val frameCount: Int = DEFAULT_FRAME_COUNT,
    private val frameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
    /** The optional depth model; not applicable when the host configures none. */
    private val depthPairs: ComparisonPairFactory<DepthFrameAgreement> = ComparisonPairFactory { ComparisonPair.notApplicable() },
    private val geometry: ConsistencyGeometry = ConsistencyGeometry.None,
) {

    suspend fun run(onFrame: (done: Int, total: Int) -> Unit = { _, _ -> }): ConsistencyRunResult {
        // Each pair is released however the run ends, including a cancellation that arrives while
        // the next pair is still being acquired.
        val semantic = semanticPairs.create()
        try {
            val detection = detectionPairs.create()
            try {
                val depth = depthPairs.create()
                try {
                    // The gravity source is started only for the depth fit, and stopped however
                    // the run ends.
                    if (!depth.ready) return compare(semantic, detection, depth, onFrame)
                    geometry.start()
                    try {
                        geometry.awaitGravity(GRAVITY_WAIT_MS)
                        return compare(semantic, detection, depth, onFrame)
                    } finally {
                        geometry.stop()
                    }
                } finally {
                    depth.release()
                }
            } finally {
                detection.release()
            }
        } finally {
            semantic.release()
        }
    }

    private suspend fun compare(
        semantic: ComparisonPair<ClassMapAgreement>,
        detection: ComparisonPair<DetectionAgreement>,
        depth: ComparisonPair<DepthFrameAgreement>,
        onFrame: (done: Int, total: Int) -> Unit,
    ): ConsistencyRunResult {
        val semFrames = mutableListOf<ClassMapAgreement>()
        val detFrames = mutableListOf<DetectionAgreement>()
        val depthFrames = mutableListOf<DepthFrameAgreement>()
        var semAccelerators = AcceleratorPair()
        var detAccelerators = AcceleratorPair()
        var depthAccelerators = AcceleratorPair()
        var framesUsed = 0
        var framesChanged = 0

        if (semantic.ready || detection.ready || depth.ready) {
            val lease = snapshots.openSnapshotLease()
            try {
                for (i in 0 until frameCount) {
                    if (i > 0) delay(frameIntervalMs)
                    val frame = snapshots.awaitCurrentFrame(maxAgeMs = FRAME_MAX_AGE_MS) ?: break
                    // Frozen now, before any model runs: see ConsistencyGeometry.
                    val consistencyFrame = ConsistencyFrame(frame, if (depth.ready) geometry.resolve(frame) else null)
                    // Both runs must see the same pixels. A snapshot is detached from the frame
                    // pool, so nothing should write into it; if something does, the comparison
                    // is void and the camera layer has a bug worth reporting.
                    val before = frameChecksum(frame)
                    val sem = if (semantic.ready) semantic.compare(consistencyFrame) else null
                    val det = if (detection.ready) detection.compare(consistencyFrame) else null
                    val dep = if (depth.ready) depth.compare(consistencyFrame) else null
                    if (frameChecksum(frame) != before) {
                        framesChanged++
                        log.warning(TAG, "Snapshot ${frame.sequenceNumber} changed during the comparison")
                        onFrame(framesUsed + framesChanged, frameCount)
                        continue
                    }
                    sem?.let { (agreement, accelerators) ->
                        semFrames += agreement
                        semAccelerators = accelerators
                    }
                    det?.let { (agreement, accelerators) ->
                        detFrames += agreement
                        detAccelerators = accelerators
                    }
                    dep?.let { (agreement, accelerators) ->
                        depthFrames += agreement
                        depthAccelerators = accelerators
                    }
                    framesUsed++
                    onFrame(framesUsed + framesChanged, frameCount)
                }
            } finally {
                lease.close()
            }
        }

        return ConsistencyRunResult(
            framesRequested = frameCount,
            framesUsed = framesUsed,
            framesChanged = framesChanged,
            semantic = semantic.outcome(framesUsed, frameCount) {
                SemanticModelResult(semAccelerators, judgeSemantic(semFrames))
            },
            detection = detection.outcome(framesUsed, frameCount) {
                DetectionModelResult(detAccelerators, judgeDetection(detFrames))
            },
            depth = depth.outcome(framesUsed, frameCount) {
                DepthModelResult(depthAccelerators, judgeDepth(depthFrames, DepthConsistencyThresholds(framesRequired = frameCount)))
            },
        )
    }

    companion object {
        const val DEFAULT_FRAME_COUNT: Int = 8
        const val DEFAULT_FRAME_INTERVAL_MS: Long = 500
        private const val FRAME_MAX_AGE_MS: Long = 200
        private const val GRAVITY_WAIT_MS: Long = 1_000
        private const val TAG = "ModelConsistency"

        /**
         * The checker over LiteRT runners for the profile's sem and det models, and the depth model when
         * the host configures one ([depthConfig] non-null and a file where the resolver points).
         */
        fun liteRt(
            context: Context,
            semanticConfig: SemanticModelConfig,
            detectionConfig: DetectionModelConfig,
            /** The production confidence threshold; detections are decoded [DEFAULT_CONFIDENCE_MARGIN] lower. */
            detectionThreshold: Float,
            modelSourceResolver: ModelSourceResolver,
            snapshots: FrameSnapshotProvider,
            log: LogService,
            depthConfig: DepthModelConfig? = null,
            geometry: ConsistencyGeometry = ConsistencyGeometry.None,
        ): ModelConsistencyChecker = ModelConsistencyChecker(
            semanticPairs = { liteRtSemanticPair(context, semanticConfig, modelSourceResolver, log) },
            detectionPairs = {
                liteRtDetectionPair(context, detectionConfig, detectionThreshold, modelSourceResolver, log)
            },
            snapshots = snapshots,
            log = log,
            depthPairs = {
                depthConfig?.let { liteRtDepthPair(context, it, modelSourceResolver, log) } ?: ComparisonPair.notApplicable()
            },
            geometry = geometry,
        )
    }
}

/** Builds the two runners of one model (or says why there are none). */
internal fun interface ComparisonPairFactory<A> {
    suspend fun create(): ComparisonPair<A>
}

/**
 * Two runners of one model, or the reason there are none. [A] is what one frame compares to.
 * [release] runs non-cancellably: it is called from `finally` blocks of a cancelled run, where an
 * ordinary suspending release would itself be cancelled and leak the sessions.
 */
internal class ComparisonPair<A>(
    val status: ModelCheckStatus,
    val failure: String? = null,
    /** The one physical model both runners load; null when not checked. */
    val modelSource: String? = null,
    private val runFrame: (suspend (ConsistencyFrame) -> Pair<A, AcceleratorPair>)? = null,
    private val releaseRunners: suspend () -> Unit = {},
) {
    val ready: Boolean get() = status == ModelCheckStatus.CHECKED

    suspend fun compare(frame: ConsistencyFrame): Pair<A, AcceleratorPair> = requireNotNull(runFrame)(frame)

    fun <R> outcome(framesUsed: Int, framesRequested: Int, result: () -> R): ModelOutcome<R> = when {
        status != ModelCheckStatus.CHECKED -> ModelOutcome(status, failure, modelSource, null)
        framesUsed == 0 -> ModelOutcome(ModelCheckStatus.NO_FRAMES, "no camera frame arrived", modelSource, null)
        framesUsed < framesRequested -> ModelOutcome(
            ModelCheckStatus.INCOMPLETE,
            "only $framesUsed of $framesRequested frames compared",
            modelSource,
            result(),
        )
        else -> ModelOutcome(status, null, modelSource, result())
    }

    suspend fun release() {
        withContext(NonCancellable) { runCatching { releaseRunners() } }
    }

    companion object {
        fun <A> notApplicable() = ComparisonPair<A>(ModelCheckStatus.NOT_APPLICABLE)

        /**
         * Initialises both runners; on any failure, cancellation included, releases whatever was
         * started before giving up.
         */
        suspend fun <A> initialize(
            name: String,
            modelSource: String,
            log: LogService,
            initialize: suspend () -> Unit,
            release: suspend () -> Unit,
            run: suspend (ConsistencyFrame) -> Pair<A, AcceleratorPair>,
        ): ComparisonPair<A> = try {
            initialize()
            ComparisonPair(ModelCheckStatus.CHECKED, null, modelSource, run, release)
        } catch (error: CancellationException) {
            withContext(NonCancellable) { runCatching { release() } }
            throw error
        } catch (error: Exception) {
            log.warning("ModelConsistency", "Consistency check: $name model could not be initialised", throwable = error)
            withContext(NonCancellable) { runCatching { release() } }
            ComparisonPair(ModelCheckStatus.UNAVAILABLE, error.message ?: error.javaClass.simpleName, modelSource)
        }
    }
}

private suspend fun liteRtSemanticPair(
    context: Context,
    config: SemanticModelConfig,
    resolver: ModelSourceResolver,
    log: LogService,
): ComparisonPair<ClassMapAgreement> {
    if (config.acceleratorBackend == ModelAcceleratorBackend.CPU) return ComparisonPair.notApplicable()
    val pinned = PinnedModelSourceResolver.pin(resolver, ModelType.SEMANTIC_SEGMENTATION, config.acceleratorBackend)
    fun segmenter(c: SemanticModelConfig) = LiteRtSemanticSegmenter(
        context = context,
        postprocessor = ClassMapPostprocessor,
        modelConfig = c,
        modelSourceResolver = pinned,
        preprocessCache = null,
        logService = log,
    )
    val candidate = segmenter(config)
    val reference = segmenter(config.onCpu())
    return ComparisonPair.initialize(
        name = "semantic",
        modelSource = pinned.pinned.label,
        log = log,
        initialize = { candidate.initialize(); reference.initialize() },
        release = { candidate.release(); reference.release() },
        run = { (frame) ->
            val cand = candidate.segment(frame).getOrThrow()
            val ref = reference.segment(frame).getOrThrow()
            compareClassMaps(reference = ref.value, candidate = cand.value) to
                AcceleratorPair(candidate = cand.runtimeInfo.accelerator, reference = ref.runtimeInfo.accelerator)
        },
    )
}

private suspend fun liteRtDetectionPair(
    context: Context,
    config: DetectionModelConfig,
    threshold: Float,
    resolver: ModelSourceResolver,
    log: LogService,
): ComparisonPair<DetectionAgreement> {
    if (config.acceleratorBackend == ModelAcceleratorBackend.CPU) return ComparisonPair.notApplicable()
    val pinned = PinnedModelSourceResolver.pin(resolver, ModelType.OBSTACLE_DETECTION, config.acceleratorBackend)
    val decodeThreshold = (threshold - DEFAULT_CONFIDENCE_MARGIN).coerceAtLeast(MIN_DECODE_CONFIDENCE)
    fun runner(c: DetectionModelConfig) = LiteRtDetectionRunner(
        context = context,
        // Every class: the check is about the model, not about which classes Guidance uses.
        allowedClassIds = IntArray(c.classCount) { it },
        confidenceThreshold = decodeThreshold,
        // Not Guidance's cap (maxObstacles = 10): with every class allowed, a production cap would
        // let the top ten hide a difference in the eleventh.
        maxDetections = DIAGNOSTIC_MAX_DETECTIONS,
        modelConfig = c,
        modelSourceResolver = pinned,
        preprocessCache = null,
        logService = log,
    )
    val candidate = runner(config)
    val reference = runner(config.onCpu())
    return ComparisonPair.initialize(
        name = "detection",
        modelSource = pinned.pinned.label,
        log = log,
        initialize = { candidate.initialize(); reference.initialize() },
        release = { candidate.release(); reference.release() },
        run = { (frame) ->
            val cand = candidate.detect(frame)
            val ref = reference.detect(frame)
            compareDetections(reference = ref.detections, candidate = cand.detections, threshold = threshold) to
                AcceleratorPair(candidate = cand.runtimeInfo.accelerator, reference = ref.runtimeInfo.accelerator)
        },
    )
}

/**
 * The depth model on its configured accelerator and on the CPU. Not applicable when the profile runs
 * it on the CPU, or when there is no depth file where the resolver points: a host without a depth
 * model has nothing to check, and that is not a failure.
 */
private suspend fun liteRtDepthPair(
    context: Context,
    config: DepthModelConfig,
    resolver: ModelSourceResolver,
    log: LogService,
): ComparisonPair<DepthFrameAgreement> {
    if (config.acceleratorBackend == ModelAcceleratorBackend.CPU) return ComparisonPair.notApplicable()
    val pinned = PinnedModelSourceResolver.pin(resolver, ModelType.DEPTH_ESTIMATION, config.acceleratorBackend)
    when (val preflight = DepthModelPreflight.check(context, pinned.pinned)) {
        is DepthModelPreflight.Result.Compatible -> Unit
        DepthModelPreflight.Result.ModelSourceMissing -> return ComparisonPair.notApplicable()
        else -> return ComparisonPair(ModelCheckStatus.UNAVAILABLE, preflight.toString(), pinned.pinned.label)
    }
    val candidate = LiteRtDepthRunner(context, config, pinned, log)
    val reference = LiteRtDepthRunner(context, config.onCpu(), pinned, log)
    // One fitter per side, fitted in the same frame order, sharing no history.
    val candidateFitter = GroundFitter()
    val referenceFitter = GroundFitter()
    return ComparisonPair.initialize(
        name = "depth",
        modelSource = pinned.pinned.label,
        log = log,
        initialize = { candidate.initialize(); reference.initialize() },
        release = { candidate.release(); reference.release() },
        run = { (frame, geometry) ->
            val cand = candidate.infer(checkNotNull(candidate.prepare(frame)) { "native preprocessing unavailable" })
            val ref = reference.infer(checkNotNull(reference.prepare(frame)) { "native preprocessing unavailable" })
            val fit = geometry?.let { compareGroundFits(it, ref.map, cand.map, referenceFitter, candidateFitter) }
            DepthFrameAgreement(compareDisparity(reference = ref.map, candidate = cand.map), fit) to
                AcceleratorPair(candidate = cand.runtimeInfo.accelerator, reference = ref.runtimeInfo.accelerator)
        },
    )
}

/** The two fits of one frame; null when the frame's geometry cannot carry a fit. */
internal fun compareGroundFits(
    geometry: CameraGeometry,
    reference: DisparityMap,
    candidate: DisparityMap,
    referenceFitter: GroundFitter,
    candidateFitter: GroundFitter,
): GroundFitAgreement? {
    val intrinsics = geometry.intrinsics ?: return null
    val down = geometry.gravityInCamera ?: return null
    if (geometry.quality is GeometryQuality.Unavailable) return null
    if (intrinsics.width != reference.transform.analysisWidth || intrinsics.height != reference.transform.analysisHeight) return null
    val r = referenceFitter.fit(reference, intrinsics, down, FIT_GRID_WIDTH, FIT_GRID_HEIGHT)
    val c = candidateFitter.fit(candidate, intrinsics, down, FIT_GRID_WIDTH, FIT_GRID_HEIGHT)
    val labels = if (r.isValid && c.isValid) {
        var same = 0
        for (y in 0 until FIT_GRID_HEIGHT) for (x in 0 until FIT_GRID_WIDTH) {
            if (r.groundMask.get(x, y) == c.groundMask.get(x, y) &&
                r.aboveGroundMask.get(x, y) == c.aboveGroundMask.get(x, y) &&
                r.belowGroundMask.get(x, y) == c.belowGroundMask.get(x, y)
            ) same++
        }
        same.toFloat() / (FIT_GRID_WIDTH * FIT_GRID_HEIGHT)
    } else {
        null
    }
    return GroundFitAgreement(validityAgrees = r.validity == c.validity, referenceValid = r.isValid, labelAgreement = labels)
}

/** The ground fit's label grid: the size the experiments measured agreement on (portrait 9:16). */
internal const val FIT_GRID_WIDTH: Int = 90
internal const val FIT_GRID_HEIGHT: Int = 160

private fun DepthModelConfig.onCpu() =
    copy(acceleratorBackend = ModelAcceleratorBackend.CPU, acceleratorSelectionMode = ModelAcceleratorSelectionMode.EXPLICIT)

private fun SemanticModelConfig.onCpu() =
    copy(acceleratorBackend = ModelAcceleratorBackend.CPU, acceleratorSelectionMode = ModelAcceleratorSelectionMode.EXPLICIT)

private fun DetectionModelConfig.onCpu() =
    copy(acceleratorBackend = ModelAcceleratorBackend.CPU, acceleratorSelectionMode = ModelAcceleratorSelectionMode.EXPLICIT)

private const val MIN_DECODE_CONFIDENCE: Float = 0.05f

/** Detections decoded per run for the comparison: model-wide, far above any production cap. */
internal const val DIAGNOSTIC_MAX_DETECTIONS: Int = 100

/** CRC32 of the frame's pixels: the YUV planes when present, otherwise the packed bytes. */
internal fun frameChecksum(frame: ImageFrame): Long {
    val crc = CRC32()
    val yuv = frame.yuvData
    if (yuv != null) {
        crc.update(yuv.y.bytes)
        crc.update(yuv.u.bytes)
        crc.update(yuv.v.bytes)
    } else {
        crc.update(frame.pixelBytes)
    }
    return crc.value
}

/** The accelerator each run reported actually using (`MlRuntimeInfo.accelerator`). */
internal data class AcceleratorPair(val candidate: String = "", val reference: String = "")

/** A snapshot and the geometry resolved for it when it was taken (null without the depth check). */
internal data class ConsistencyFrame(val frame: ImageFrame, val geometry: CameraGeometry? = null)

internal enum class ModelCheckStatus {
    CHECKED,

    /** The profile already runs this model on the CPU: there is no second answer to compare. */
    NOT_APPLICABLE,

    /** No model file, or one of the two runners failed to start. */
    UNAVAILABLE,

    /** The camera delivered no frame (no preview bound, or permission missing). */
    NO_FRAMES,

    /** Compared, but on fewer frames than requested: not enough evidence to pass. */
    INCOMPLETE,
}

internal data class ModelOutcome<R>(
    val status: ModelCheckStatus,
    val failure: String?,
    val modelSource: String?,
    val result: R?,
)

/**
 * The verdict this model's evidence supports: its comparison verdict when every frame was compared;
 * on partial evidence a failure still fails but anything else is inconclusive; null when nothing
 * was compared.
 */
internal fun effectiveVerdict(status: ModelCheckStatus, compared: ConsistencyVerdict?): ConsistencyVerdict? = when (status) {
    ModelCheckStatus.CHECKED -> compared
    ModelCheckStatus.INCOMPLETE ->
        if (compared == ConsistencyVerdict.FAIL) ConsistencyVerdict.FAIL else ConsistencyVerdict.INCONCLUSIVE
    else -> null
}

internal data class SemanticModelResult(
    val accelerators: AcceleratorPair,
    val consistency: SemanticConsistency,
)

internal data class DetectionModelResult(
    val accelerators: AcceleratorPair,
    val consistency: DetectionConsistency,
)

internal data class DepthModelResult(
    val accelerators: AcceleratorPair,
    val consistency: DepthConsistency,
)

internal data class ConsistencyRunResult(
    val framesRequested: Int,
    val framesUsed: Int,
    /** Snapshots whose pixels changed between the two runs; left out of the comparison. */
    val framesChanged: Int,
    val semantic: ModelOutcome<SemanticModelResult>,
    val detection: ModelOutcome<DetectionModelResult>,
    val depth: ModelOutcome<DepthModelResult> = ModelOutcome(ModelCheckStatus.NOT_APPLICABLE, null, null, null),
) {
    val semanticVerdict: ConsistencyVerdict?
        get() = effectiveVerdict(semantic.status, semantic.result?.consistency?.verdict)

    val detectionVerdict: ConsistencyVerdict?
        get() = effectiveVerdict(detection.status, detection.result?.consistency?.verdict)

    val depthVerdict: ConsistencyVerdict?
        get() = effectiveVerdict(depth.status, depth.result?.consistency?.verdict)

    private val models: List<Pair<ModelCheckStatus, ConsistencyVerdict?>>
        get() = listOf(semantic.status to semanticVerdict, detection.status to detectionVerdict, depth.status to depthVerdict)

    /** Models the profile runs on an accelerator, so there is something to compare. */
    val modelsApplicable: Int get() = models.count { it.first != ModelCheckStatus.NOT_APPLICABLE }

    /** Of those, how many were compared on every requested frame. */
    val modelsChecked: Int get() = models.count { it.first == ModelCheckStatus.CHECKED }

    /** See [overallVerdict]. */
    val overall: ConsistencyVerdict? get() = overallVerdict(models)
}

/**
 * PASS means every model there was something to compare for was compared on every frame and passed
 * -- not "no failure among the ones that ran". Any failure fails; anything not compared (no model,
 * no frames), compared on too few frames, or inconclusive leaves the run inconclusive. Null when no
 * model runs on an accelerator. [models] carry effective verdicts ([effectiveVerdict]).
 */
internal fun overallVerdict(models: List<Pair<ModelCheckStatus, ConsistencyVerdict?>>): ConsistencyVerdict? {
    val applicable = models.filter { it.first != ModelCheckStatus.NOT_APPLICABLE }
    return when {
        applicable.isEmpty() -> null
        applicable.any { it.second == ConsistencyVerdict.FAIL } -> ConsistencyVerdict.FAIL
        applicable.all { it.first == ModelCheckStatus.CHECKED && it.second == ConsistencyVerdict.PASS } -> ConsistencyVerdict.PASS
        else -> ConsistencyVerdict.INCONCLUSIVE
    }
}
