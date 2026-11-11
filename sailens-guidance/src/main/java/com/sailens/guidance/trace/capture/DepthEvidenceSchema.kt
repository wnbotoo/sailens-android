package com.sailens.guidance.trace.capture

import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.trace.regression.regressionCrc32
import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.ResizePolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * The depth evidence stream (docs/field-capture.md §11), since schema 1.9: one [DepthEvidenceRecord] per depth
 * run that produced an output, in `depth.jsonl`, with the frame's planes and the model's output in
 * `depth.bin`. It holds what redoing the run needs -- the planes exactly as the native preprocessing read
 * them, the geometry the fit used (never re-resolved from the sensor log: the live lane used the sample it
 * had), the input transform, the output and the fit's result -- so a PC can rerun the preprocessing and the
 * model, and the JVM can rerun the fit in order from the session's first one.
 */
object DepthEvidenceFiles {
    const val RECORDS_FILE: String = "depth.jsonl"
    const val DATA_FILE: String = "depth.bin"
}

/**
 * @param generation the depth session (the lane's generation) the run belongs to.
 * @param runId the run's id in that session, as in its `depth_run` record.
 * @param fitOrdinal which call of the session's fitter the run made, from 0 at the session's start; null when
 *   the fitter was not called (a non-finite output). The fit can be replayed only over an unbroken run of
 *   these from 0: a missing ordinal is a run whose record was lost, and its history with it.
 * @param outcome the run's `depth_run` outcome (`DepthRunTrace.PUBLISHED` or `DepthRunTrace.NON_FINITE_OUTPUT`
 *   today), an open string.
 * @param sensorTimestampNanos the camera's frame timestamp.
 */
@Serializable
@SerialName("depth_evidence")
data class DepthEvidenceRecord(
    val generation: Long,
    val runId: Long,
    val fitOrdinal: Long? = null,
    val outcome: String,
    val frameSequence: Long,
    val sensorTimestampNanos: Long,
    val frame: DepthFrameRecord,
    val geometry: GeometryRecord,
    val transform: DepthTransformRecord,
    val gridWidth: Int,
    val gridHeight: Int,
    val output: DepthOutputRecord,
    val observation: DepthObservationRecord? = null,
    val modelShape: String,
    val accelerator: String? = null,
    val detail: Float? = null,
) : CaptureRecord

/**
 * The frame as the lane received it: [width] × [height] before rotation, and its three planes. Each plane's
 * bytes are the whole array the native preprocessing was given, strides kept: it reads sample (x, y) at
 * `y × rowStride + x × pixelStride`, and a sample past the end of the array as its plane's default (16 for Y,
 * 128 for U and V).
 */
@Serializable
data class DepthFrameRecord(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val y: DepthPlaneRecord,
    val u: DepthPlaneRecord,
    val v: DepthPlaneRecord,
)

/** One plane: its size in samples ((width + 1) / 2 for U and V), its strides and where its bytes are. */
@Serializable
data class DepthPlaneRecord(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val data: DepthBlobRef,
)

/** Bytes in `depth.bin`: [offset], [length] and the CRC32 of exactly those bytes. */
@Serializable
data class DepthBlobRef(
    val offset: Long,
    val length: Int,
    val crc32: Long,
)

/** The model's output: [width] × [height] float32, row-major, little-endian. */
@Serializable
data class DepthOutputRecord(
    val width: Int,
    val height: Int,
    val data: DepthBlobRef,
)

/** `DepthInputTransform`, every field; [policy] by name. */
@Serializable
data class DepthTransformRecord(
    val analysisWidth: Int,
    val analysisHeight: Int,
    val modelWidth: Int,
    val modelHeight: Int,
    val policy: String,
    val sourceLeft: Float,
    val sourceTop: Float,
    val sourceWidth: Float,
    val sourceHeight: Float,
    val scaleX: Float,
    val scaleY: Float,
    val padLeft: Float,
    val padTop: Float,
)

/**
 * The fit's result. The masks are on the [DepthEvidenceRecord.gridWidth] × [DepthEvidenceRecord.gridHeight]
 * grid and stored as their CRC32 (`regressionCrc32`: size and bits) and cell count: a replay that computes
 * the same CRCs computed the same masks.
 */
@Serializable
data class DepthObservationRecord(
    /** `GroundFitValidity` by name, lower case; open. */
    val validity: String,
    val frameTimeNanos: Long? = null,
    val inlierRatio: Float,
    val confidence: Float,
    val planeAgreesWithGravity: Boolean,
    val disparityScale: Float,
    val disparityShift: Float,
    val groundMaskCrc32: Long,
    val aboveGroundMaskCrc32: Long,
    val belowGroundMaskCrc32: Long,
    val groundCells: Int,
    val aboveGroundCells: Int,
    val belowGroundCells: Int,
)

/**
 * [CaptureManifest.depthEvidence]: present when the session recorded the stream. [poolSize] buffers hold the
 * planes between the lane and the writer, and [queueCapacity] records wait for it; a run that finds neither
 * free is dropped and counted.
 */
@Serializable
data class DepthEvidenceManifest(
    val poolSize: Int,
    val queueCapacity: Int,
    val stats: DepthEvidenceStats = DepthEvidenceStats(),
)

/**
 * The stream's counters, final when the capture is complete. [dropped] (`capture_dropped`) counts every run the
 * stream was offered and did not write -- no free buffer or nothing it could copy, a full queue, a failed write,
 * the capture ending first -- and is the sum of the four reasons. A replay is acceptable only up to the first dropped
 * run, whether or not it made a fit, and the first missing fit ordinal (docs/field-capture.md §11).
 */
@Serializable
data class DepthEvidenceStats(
    val recordsWritten: Long = 0,
    val dropped: Long = 0,
    val droppedNoBuffer: Long = 0,
    val droppedQueueFull: Long = 0,
    val droppedWriteFailed: Long = 0,
    val droppedAtEnd: Long = 0,
    /** The run id of the first dropped run, and its depth session. */
    val firstDroppedRunId: Long? = null,
    val firstDroppedGeneration: Long? = null,
    val bytes: Long = 0,
)

/**
 * `depth.bin`: blobs one after another and nothing else; only the records say where each one is. A torn or
 * damaged blob is found by its CRC32, never read as data.
 */
object DepthEvidenceData {
    fun ref(offset: Long, bytes: ByteArray, length: Int = bytes.size): DepthBlobRef =
        DepthBlobRef(offset, length, CRC32().apply { update(bytes, 0, length) }.value)

    /** @throws IOException when the blob is not wholly in [file] or its CRC32 differs. */
    fun read(file: File, ref: DepthBlobRef): ByteArray = RandomAccessFile(file, "r").use { data ->
        if (ref.offset < 0 || ref.length < 0 || ref.offset + ref.length > data.length()) {
            throw IOException("depth blob ${ref.offset}+${ref.length} is not in ${file.name} (${data.length()} bytes)")
        }
        val bytes = ByteArray(ref.length)
        data.seek(ref.offset)
        data.readFully(bytes)
        if (CRC32().apply { update(bytes) }.value != ref.crc32) throw IOException("depth blob at ${ref.offset}: CRC32 mismatch")
        bytes
    }

    /** Float32 little-endian [bytes] as [count] floats. */
    fun floats(bytes: ByteArray, count: Int): FloatArray {
        if (bytes.size != count * Float.SIZE_BYTES) throw IOException("expected ${count * Float.SIZE_BYTES} bytes of float32, got ${bytes.size}")
        return FloatArray(count).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }

    /** [values] as float32 little-endian, into [into] when it is large enough. */
    fun floatBytes(values: FloatArray, into: ByteArray? = null): ByteArray {
        val size = values.size * Float.SIZE_BYTES
        val bytes = into?.takeIf { it.size >= size } ?: ByteArray(size)
        ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(values)
        return bytes
    }
}

fun DepthInputTransform.toRecord(): DepthTransformRecord = DepthTransformRecord(
    analysisWidth, analysisHeight, modelWidth, modelHeight, policy.name,
    sourceLeft, sourceTop, sourceWidth, sourceHeight, scaleX, scaleY, padLeft, padTop,
)

fun DepthTransformRecord.toTransform(): DepthInputTransform = DepthInputTransform(
    analysisWidth, analysisHeight, modelWidth, modelHeight, ResizePolicy.valueOf(policy),
    sourceLeft, sourceTop, sourceWidth, sourceHeight, scaleX, scaleY, padLeft, padTop,
)

fun GroundObservation.toRecord(): DepthObservationRecord = DepthObservationRecord(
    validity = validity.name.lowercase(),
    frameTimeNanos = frameTimeNanos,
    inlierRatio = inlierRatio,
    confidence = confidence,
    planeAgreesWithGravity = planeAgreesWithGravity,
    disparityScale = disparityScale,
    disparityShift = disparityShift,
    groundMaskCrc32 = groundMask.regressionCrc32(),
    aboveGroundMaskCrc32 = aboveGroundMask.regressionCrc32(),
    belowGroundMaskCrc32 = belowGroundMask.regressionCrc32(),
    groundCells = groundMask.countTrue(),
    aboveGroundCells = aboveGroundMask.countTrue(),
    belowGroundCells = belowGroundMask.countTrue(),
)
