package com.sailens.vision.depth

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litert.Accelerator
import com.sailens.core.frame.ImageFrame
import com.sailens.core.log.LogService
import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.runtime.InputPreprocessBackend
import com.sailens.runtime.ModelInputPreprocessor
import com.sailens.runtime.ModelInputQuantization
import com.sailens.runtime.ModelSourceResolver
import com.sailens.runtime.ModelTensorConfig
import com.sailens.runtime.ModelType
import com.sailens.runtime.TfliteModelMetadataReader
import com.sailens.runtime.session.AcceleratorSelection
import com.sailens.runtime.session.LiteRtSession
import com.sailens.runtime.session.LiteRtSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

private const val TAG = "LiteRtDepthRunner"

/**
 * The depth model, split at the point where the camera frame can be given back: [prepare] does the
 * CPU work that reads the frame (native letterbox into the runner's own input array, the image detail
 * measure), and [infer] does the GPU work that does not (write the tensor, run, read the output). A
 * caller returns the frame between the two, so nothing holds a pooled camera frame while it waits
 * for the GPU.
 *
 * Native preprocessing only: the OpenCV fallback truncates sizes and offsets to integers and would
 * not match [DepthInputTransform], so a frame the native path cannot take is reported, not converted
 * another way. No shared preprocessing cache: the depth tensor is never the semantic one's.
 *
 * One prepare/infer pair at a time, on the runner's own single thread, like the other runners.
 */
class LiteRtDepthRunner(
    private val context: Context,
    private val modelConfig: DepthModelConfig,
    private val modelSourceResolver: ModelSourceResolver,
    private val logService: LogService,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    private var bound: Bound? = null
    private var preparedSerial = 0L

    val spec: DepthTensorSpec? get() = bound?.spec

    suspend fun initialize() = withContext(dispatcher) {
        bound?.close()
        bound = null
        bound = bind()
    }

    /** Null, with nothing written, when the native preprocessing could not take this frame. */
    suspend fun prepare(frame: ImageFrame): DepthPrepared? = withContext(dispatcher) {
        val b = checkNotNull(bound) { "depth runner not initialized" }
        val start = SystemClock.uptimeMillis()
        val rotated = frame.rotationDegrees.mod(360).let { it == 90 || it == 270 }
        val analysisWidth = if (rotated) frame.height else frame.width
        val analysisHeight = if (rotated) frame.width else frame.height
        val transform = DepthInputTransform.create(
            analysisWidth, analysisHeight, b.spec.inputWidth, b.spec.inputHeight, ResizePolicy.PAD_KEEP_ASPECT,
        )
        if (!b.preprocessor.preprocessFloatNativeOnly(frame, frame.rotationDegrees, b.input)) return@withContext null
        val detail = ImageDetail.measure(frame)
        preparedSerial++
        DepthPrepared(
            serial = preparedSerial,
            transform = transform,
            detail = detail,
            preprocessMs = SystemClock.uptimeMillis() - start,
        )
    }

    /** Runs the model on the input [prepared] wrote. Only the most recent [prepare] can be inferred. */
    suspend fun infer(prepared: DepthPrepared): DepthRun = withContext(dispatcher) {
        val b = checkNotNull(bound) { "depth runner not initialized" }
        check(prepared.serial == preparedSerial) { "a later prepare overwrote this input" }
        val start = SystemClock.uptimeMillis()
        b.session.inputBuffers.single().writeFloat(b.input)
        b.session.run()
        val afterRun = SystemClock.uptimeMillis()
        val values = b.session.outputBuffers.single().readFloat()
        val afterRead = SystemClock.uptimeMillis()
        DepthRun(
            map = DisparityMap(b.spec.outputWidth, b.spec.outputHeight, values, prepared.transform),
            nonFiniteCount = values.count { !it.isFinite() },
            detail = prepared.detail,
            preprocessMs = prepared.preprocessMs,
            inferenceMs = afterRun - start,
            outputReadMs = afterRead - afterRun,
            runtimeInfo = b.runtimeInfo,
            shapeLabel = b.spec.shapeLabel,
        )
    }

    suspend fun release() = withContext(dispatcher) {
        bound?.close()
        bound = null
    }

    private fun bind(): Bound {
        val session = LiteRtSessionFactory.create(
            context = context,
            sourceResolver = { accelerator -> modelSourceResolver.source(ModelType.DEPTH_ESTIMATION, accelerator) },
            selection = AcceleratorSelection(
                mode = modelConfig.acceleratorSelectionMode,
                preferredBackend = modelConfig.acceleratorBackend,
                fallbackOrder = ACCELERATOR_FALLBACK_ORDER,
            ),
            logTag = TAG,
            modelLabel = "depth model",
            logService = logService,
            gpuSettings = modelConfig.gpuSettings,
        )
        return try {
            val metadata = TfliteModelMetadataReader.read(context, session.source)
            val spec = when (val resolved = DepthTensorContract.resolve(metadata)) {
                is DepthTensorContract.Resolution.Compatible -> resolved.spec
                is DepthTensorContract.Resolution.InputUnreadable -> error("depth input: ${resolved.detail}")
                is DepthTensorContract.Resolution.OutputUnreadable -> error("depth output: ${resolved.detail}")
                is DepthTensorContract.Resolution.OutputSizeMismatch -> error("depth output: ${resolved.detail}")
            }
            val tensorConfig = ModelTensorConfig(
                inputWidth = spec.inputWidth,
                inputHeight = spec.inputHeight,
                inputLayout = spec.inputLayout,
                outputWidth = spec.outputWidth,
                outputHeight = spec.outputHeight,
                outputChannels = 1,
                // The contract puts any normalization inside the model: the input is RGB in [0, 1].
                mean = Triple(0f, 0f, 0f),
                std = Triple(1f, 1f, 1f),
                resizeFilter = modelConfig.resizeFilter,
            )
            val selectionLabel = if (session.accelerator == Accelerator.GPU) {
                "${session.acceleratorSelection} precision=${modelConfig.gpuPrecision} priority=${modelConfig.gpuPriority}"
            } else {
                session.acceleratorSelection
            }
            logService.info(TAG, "depth model ${session.source.label}: ${spec.shapeLabel} on ${session.accelerator} ($selectionLabel)")
            Bound(
                session = session,
                spec = spec,
                preprocessor = ModelInputPreprocessor(
                    config = tensorConfig,
                    inputQuantization = ModelInputQuantization(),
                    preferNativeYuvPreprocessing = true,
                    preprocessCache = null,
                ),
                input = FloatArray(spec.inputWidth * spec.inputHeight * 3),
                runtimeInfo = MlRuntimeInfo(
                    accelerator = session.accelerator.name,
                    acceleratorSelection = selectionLabel,
                    preprocessBackend = InputPreprocessBackend.NATIVE_YUV.traceName,
                    postprocessBackend = POSTPROCESS_BACKEND,
                ),
            )
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    private class Bound(
        val session: LiteRtSession,
        val spec: DepthTensorSpec,
        val preprocessor: ModelInputPreprocessor,
        val input: FloatArray,
        val runtimeInfo: MlRuntimeInfo,
    ) {
        fun close() {
            runCatching { preprocessor.close() }
            session.close()
        }
    }

    companion object {
        /** The ground fit that consumes the map runs on the CPU, in Kotlin. */
        const val POSTPROCESS_BACKEND: String = "kotlin_ground_fit"
        private val ACCELERATOR_FALLBACK_ORDER = listOf(Accelerator.GPU, Accelerator.CPU)
    }
}

/** A frame's input, written into the runner's tensor by [LiteRtDepthRunner.prepare]. */
class DepthPrepared internal constructor(
    internal val serial: Long,
    val transform: DepthInputTransform,
    val detail: Float?,
    val preprocessMs: Long,
)

/** One depth inference: the disparity map in the frame's geometry, and what it took. */
class DepthRun(
    val map: DisparityMap,
    /** NaN or infinite values in the output; any is a failed run (docs/models.md). */
    val nonFiniteCount: Int,
    val detail: Float?,
    val preprocessMs: Long,
    val inferenceMs: Long,
    val outputReadMs: Long,
    val runtimeInfo: MlRuntimeInfo,
    val shapeLabel: String,
)
