package com.sailens.shell.consistency

import android.content.Context
import com.sailens.camera.FrameSnapshotProvider
import com.sailens.core.frame.ImageFrame
import com.sailens.core.log.LogService
import com.sailens.runtime.ModelAcceleratorBackend
import com.sailens.runtime.ModelAcceleratorSelectionMode
import com.sailens.runtime.ModelSourceResolver
import com.sailens.vision.consistency.ClassMapAgreement
import com.sailens.vision.consistency.ConsistencyVerdict
import com.sailens.vision.consistency.DEFAULT_CONFIDENCE_MARGIN
import com.sailens.vision.consistency.DetectionAgreement
import com.sailens.vision.consistency.DetectionConsistency
import com.sailens.vision.consistency.SemanticConsistency
import com.sailens.vision.consistency.compareClassMaps
import com.sailens.vision.consistency.compareDetections
import com.sailens.vision.consistency.judgeDetection
import com.sailens.vision.consistency.judgeSemantic
import com.sailens.vision.detection.DetectionModelConfig
import com.sailens.vision.detection.LiteRtDetectionRunner
import com.sailens.vision.semantic.ClassMapPostprocessor
import com.sailens.vision.semantic.LiteRtSemanticSegmenter
import com.sailens.vision.semantic.SemanticModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.zip.CRC32

/**
 * Runs each model twice on the same camera frames -- once on the accelerator the runtime profile
 * selects, once on the CPU -- and compares the two answers (`AcceleratorConsistency`).
 *
 * The two runs of a model differ only in the accelerator: the same model file, the same frame, no
 * shared preprocessing cache. The CPU answer is the reference. A model the profile already runs on
 * the CPU has nothing to compare and is reported as such.
 *
 * Results do not depend on GPU contention, only timings do; Guidance is still kept stopped by the
 * caller so the check never competes with live guidance for the camera or the GPU.
 */
internal class ModelConsistencyChecker(
    private val context: Context,
    private val semanticConfig: SemanticModelConfig,
    private val detectionConfig: DetectionModelConfig,
    /** The production confidence threshold; detections are decoded [DEFAULT_CONFIDENCE_MARGIN] lower. */
    private val detectionThreshold: Float,
    private val maxDetections: Int,
    private val modelSourceResolver: ModelSourceResolver,
    private val snapshots: FrameSnapshotProvider,
    private val log: LogService,
    private val frameCount: Int = DEFAULT_FRAME_COUNT,
    private val frameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
) {

    suspend fun run(onFrame: (done: Int, total: Int) -> Unit = { _, _ -> }): ConsistencyRunResult {
        val semantic = semanticPair()
        val detection = detectionPair()
        try {
            val semFrames = mutableListOf<ClassMapAgreement>()
            val detFrames = mutableListOf<DetectionAgreement>()
            var semAccelerators = AcceleratorPair()
            var detAccelerators = AcceleratorPair()
            var framesUsed = 0
            var framesChanged = 0

            if (semantic.ready || detection.ready) {
                val lease = snapshots.openSnapshotLease()
                try {
                    for (i in 0 until frameCount) {
                        if (i > 0) delay(frameIntervalMs)
                        val frame = snapshots.awaitCurrentFrame(maxAgeMs = FRAME_MAX_AGE_MS) ?: break
                        // Both runs must see the same pixels. A snapshot is detached from the
                        // frame pool, so nothing should write into it; if something does, the
                        // comparison is void and the camera layer has a bug worth reporting.
                        val before = frameChecksum(frame)
                        val sem = if (semantic.ready) semantic.compare(frame) else null
                        val det = if (detection.ready) detection.compare(frame) else null
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
                semantic = semantic.outcome(framesUsed) {
                    SemanticModelResult(semAccelerators, judgeSemantic(semFrames))
                },
                detection = detection.outcome(framesUsed) {
                    DetectionModelResult(detAccelerators, judgeDetection(detFrames))
                },
            )
        } finally {
            semantic.release()
            detection.release()
        }
    }

    private suspend fun semanticPair(): RunnerPair<ClassMapAgreement> {
        if (semanticConfig.acceleratorBackend == ModelAcceleratorBackend.CPU) return RunnerPair.notApplicable()
        fun segmenter(config: SemanticModelConfig) = LiteRtSemanticSegmenter(
            context = context,
            postprocessor = ClassMapPostprocessor,
            modelConfig = config,
            modelSourceResolver = modelSourceResolver,
            preprocessCache = null,
            logService = log,
        )
        val candidate = segmenter(semanticConfig)
        val reference = segmenter(semanticConfig.copy(acceleratorBackend = ModelAcceleratorBackend.CPU, acceleratorSelectionMode = ModelAcceleratorSelectionMode.EXPLICIT))
        return RunnerPair.initialize(
            name = "semantic",
            log = log,
            initialize = { candidate.initialize(); reference.initialize() },
            release = { candidate.release(); reference.release() },
            run = { frame ->
                val cand = candidate.segment(frame).getOrThrow()
                val ref = reference.segment(frame).getOrThrow()
                compareClassMaps(reference = ref.value, candidate = cand.value) to
                    AcceleratorPair(candidate = cand.runtimeInfo.accelerator, reference = ref.runtimeInfo.accelerator)
            },
        )
    }

    private suspend fun detectionPair(): RunnerPair<DetectionAgreement> {
        if (detectionConfig.acceleratorBackend == ModelAcceleratorBackend.CPU) return RunnerPair.notApplicable()
        val decodeThreshold = (detectionThreshold - DEFAULT_CONFIDENCE_MARGIN).coerceAtLeast(MIN_DECODE_CONFIDENCE)
        fun runner(config: DetectionModelConfig) = LiteRtDetectionRunner(
            context = context,
            // Every class: the check is about the model, not about which classes Guidance uses.
            allowedClassIds = IntArray(detectionConfig.classCount) { it },
            confidenceThreshold = decodeThreshold,
            maxDetections = maxDetections,
            modelConfig = config,
            modelSourceResolver = modelSourceResolver,
            preprocessCache = null,
            logService = log,
        )
        val candidate = runner(detectionConfig)
        val reference = runner(detectionConfig.copy(acceleratorBackend = ModelAcceleratorBackend.CPU, acceleratorSelectionMode = ModelAcceleratorSelectionMode.EXPLICIT))
        return RunnerPair.initialize(
            name = "detection",
            log = log,
            initialize = { candidate.initialize(); reference.initialize() },
            release = { candidate.release(); reference.release() },
            run = { frame ->
                val cand = candidate.detect(frame)
                val ref = reference.detect(frame)
                compareDetections(reference = ref.detections, candidate = cand.detections, threshold = detectionThreshold) to
                    AcceleratorPair(candidate = cand.runtimeInfo.accelerator, reference = ref.runtimeInfo.accelerator)
            },
        )
    }

    /** Two runners of one model, or the reason there are none. [A] is what one frame compares to. */
    private class RunnerPair<A>(
        val status: ModelCheckStatus,
        val failure: String?,
        private val runFrame: (suspend (ImageFrame) -> Pair<A, AcceleratorPair>)?,
        private val releaseRunners: suspend () -> Unit,
    ) {
        val ready: Boolean get() = status == ModelCheckStatus.CHECKED

        suspend fun compare(frame: ImageFrame): Pair<A, AcceleratorPair> = requireNotNull(runFrame)(frame)

        fun <R> outcome(framesUsed: Int, result: () -> R): ModelOutcome<R> = when {
            status != ModelCheckStatus.CHECKED -> ModelOutcome(status, failure, null)
            framesUsed == 0 -> ModelOutcome(ModelCheckStatus.NO_FRAMES, "no camera frame arrived", null)
            else -> ModelOutcome(status, null, result())
        }

        suspend fun release() {
            runCatching { releaseRunners() }
        }

        companion object {
            fun <A> notApplicable() = RunnerPair<A>(ModelCheckStatus.NOT_APPLICABLE, null, null) {}

            suspend fun <A> initialize(
                name: String,
                log: LogService,
                initialize: suspend () -> Unit,
                release: suspend () -> Unit,
                run: suspend (ImageFrame) -> Pair<A, AcceleratorPair>,
            ): RunnerPair<A> = try {
                initialize()
                RunnerPair(ModelCheckStatus.CHECKED, null, run, release)
            } catch (error: CancellationException) {
                runCatching { release() }
                throw error
            } catch (error: Exception) {
                log.warning(TAG, "Consistency check: $name model could not be initialised", throwable = error)
                runCatching { release() }
                RunnerPair(ModelCheckStatus.UNAVAILABLE, error.message ?: error.javaClass.simpleName, null) {}
            }
        }
    }

    companion object {
        const val DEFAULT_FRAME_COUNT: Int = 8
        const val DEFAULT_FRAME_INTERVAL_MS: Long = 500
        private const val FRAME_MAX_AGE_MS: Long = 200
        private const val MIN_DECODE_CONFIDENCE: Float = 0.05f
        private const val TAG = "ModelConsistency"
    }
}

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

internal enum class ModelCheckStatus {
    CHECKED,

    /** The profile already runs this model on the CPU: there is no second answer to compare. */
    NOT_APPLICABLE,

    /** No model file, or one of the two runners failed to start. */
    UNAVAILABLE,

    /** The camera delivered no frame (no preview bound, or permission missing). */
    NO_FRAMES,
}

internal data class ModelOutcome<R>(val status: ModelCheckStatus, val failure: String?, val result: R?)

internal data class SemanticModelResult(
    val accelerators: AcceleratorPair,
    val consistency: SemanticConsistency,
)

internal data class DetectionModelResult(
    val accelerators: AcceleratorPair,
    val consistency: DetectionConsistency,
)

internal data class ConsistencyRunResult(
    val framesRequested: Int,
    val framesUsed: Int,
    /** Snapshots whose pixels changed between the two runs; left out of the comparison. */
    val framesChanged: Int,
    val semantic: ModelOutcome<SemanticModelResult>,
    val detection: ModelOutcome<DetectionModelResult>,
) {
    /** The worst verdict over the models that were checked; null when none was. */
    val overall: ConsistencyVerdict?
        get() {
            val verdicts = listOfNotNull(semantic.result?.consistency?.verdict, detection.result?.consistency?.verdict)
            return when {
                verdicts.isEmpty() -> null
                ConsistencyVerdict.FAIL in verdicts -> ConsistencyVerdict.FAIL
                ConsistencyVerdict.INCONCLUSIVE in verdicts -> ConsistencyVerdict.INCONCLUSIVE
                else -> ConsistencyVerdict.PASS
            }
        }
}
