package com.sailens.guidance.trace.capture

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Field capture format (M0a, docs/local-navigation-implementation.md §4).
 *
 * One directory per Guidance session, named after the trace session id:
 *
 * ```
 * captures/<sessionId>/
 *   manifest.json      CaptureManifest; written first with complete=false
 *   frames.jsonl       FrameRecord per stored frame
 *   frames/            the stored images (FrameRecord.file)
 *   sensors.jsonl      SensorRecord per sensor event
 *   anchors.jsonl      ClockAnchorRecord at start, periodically and at the end
 *   markers.jsonl      MarkerRecord per "missed alert" press
 * ```
 *
 * Every JSONL line is an object with a `type`. Readers ignore unknown fields and unknown record
 * types (a newer minor version may add them) and refuse an unknown major version.
 *
 * The prompt outcomes and frame traces of the same session live in the trace files and are joined
 * by `sessionId` and frame `sequenceNumber`.
 */
object CaptureSchema {
    const val MAJOR: Int = 1
    const val MINOR: Int = 0

    const val MANIFEST_FILE: String = "manifest.json"
    const val FRAMES_FILE: String = "frames.jsonl"
    const val FRAMES_DIR: String = "frames"
    const val SENSORS_FILE: String = "sensors.jsonl"
    const val ANCHORS_FILE: String = "anchors.jsonl"
    const val MARKERS_FILE: String = "markers.jsonl"

    internal const val TYPE_KEY: String = "type"

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        classDiscriminator = TYPE_KEY
    }

    fun encodeManifest(manifest: CaptureManifest): String =
        json.encodeToString(CaptureManifest.serializer(), manifest)

    /** One JSONL line (without the newline), carrying its `type`. */
    fun encodeRecord(record: CaptureRecord): String =
        json.encodeToString(CaptureRecord.serializer(), record)
}

@Serializable
enum class CaptureMode {
    /** Reduced frames (5 Hz, 640 px JPEG) + sensors + camera facts: labelling and geometry. */
    @SerialName("field_evidence") FIELD_EVIDENCE,

    /**
     * A short burst of small luma frames at camera rate for measuring frame-to-sensor alignment.
     * Never a performance baseline: it changes the load on the device.
     */
    @SerialName("timing_sync") TIMING_SYNC,
    // "model_regression" is reserved for M0b.
}

@Serializable
data class CaptureManifest(
    val schemaMajor: Int = CaptureSchema.MAJOR,
    val schemaMinor: Int = CaptureSchema.MINOR,
    val sessionId: String,
    val captureMode: CaptureMode,
    val startedWallMs: Long,
    val startedElapsedRealtimeNanos: Long,
    val appVersionName: String? = null,
    val appVersionCode: Long? = null,
    val gitSha: String? = null,
    val deviceManufacturer: String,
    val deviceModel: String,
    val sdkInt: Int,
    val targetHardwareProfile: String? = null,
    val camera: CaptureCameraRecord? = null,
    /** False until the session ended normally; a crash, kill or full disk leaves it false. */
    val complete: Boolean = false,
    val failureReason: String? = null,
    val endedWallMs: Long? = null,
    val pinned: Boolean = false,
    val exportedAtWallMs: Long? = null,
    val stats: CaptureStats = CaptureStats(),
)

/** Capture's own counters, kept apart from Guidance's dropped frames on purpose. */
@Serializable
data class CaptureStats(
    val framesOffered: Long = 0,
    val framesEncoded: Long = 0,
    val framesDroppedByEncoder: Long = 0,
    val sensorEvents: Long = 0,
    val markers: Long = 0,
)

/** Raw camera facts as reported by Camera2 for this session's binding (see `CameraCharacteristicsSnapshot`). */
@Serializable
data class CaptureCameraRecord(
    val cameraId: String,
    val capturedAtElapsedRealtimeNanos: Long,
    val sensorOrientationDegrees: Int? = null,
    /** "realtime" | "unknown" | "not_reported". */
    val timestampSource: String,
    val lensIntrinsicCalibration: List<Float>? = null,
    val lensDistortion: List<Float>? = null,
    val lensPoseRotation: List<Float>? = null,
    val lensPoseTranslation: List<Float>? = null,
    val activeArray: List<Int>? = null,
    val preCorrectionActiveArray: List<Int>? = null,
    val pixelArrayWidth: Int? = null,
    val pixelArrayHeight: Int? = null,
    val physicalSizeWidthMm: Float? = null,
    val physicalSizeHeightMm: Float? = null,
    val availableFocalLengthsMm: List<Float> = emptyList(),
)

@Serializable
sealed interface CaptureRecord

/**
 * @param sensorTimestampNanos the camera's frame timestamp (`ImageFrame.timestamp`); its time base
 *   is the manifest's `camera.timestampSource`.
 * @param receivedElapsedRealtimeNanos when the analyzer received the frame, taken at the source
 *   before any queueing; 0 when unknown.
 * @param sourceWidth analysis frame size before downscaling, as delivered (not the requested size).
 */
@Serializable
@SerialName("frame")
data class FrameRecord(
    val seq: Long,
    val sensorTimestampNanos: Long,
    val receivedElapsedRealtimeNanos: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int,
    val encoding: FrameEncoding,
    /** Path relative to the session directory. */
    val file: String,
    val width: Int,
    val height: Int,
) : CaptureRecord

@Serializable
enum class FrameEncoding {
    /** Downscaled frame, not rotated (apply [FrameRecord.rotationDegrees] to view it upright). */
    @SerialName("jpeg") JPEG,

    /** Raw 8-bit luma, row-major, `width × height` bytes, not rotated. TIMING_SYNC only. */
    @SerialName("luma8") LUMA8,
}

/**
 * One sensor event. [timestampNanos] is `SensorEvent.timestamp` (elapsed-realtime base). The
 * delivery rate is whatever the device gave; compute cadence from timestamps, not from the
 * requested delay.
 */
@Serializable
@SerialName("sensor")
data class SensorRecord(
    val sensor: CaptureSensor,
    val timestampNanos: Long,
    val accuracy: Int,
    val values: List<Float>,
) : CaptureRecord

@Serializable
enum class CaptureSensor {
    @SerialName("gravity") GRAVITY,
    @SerialName("game_rotation_vector") GAME_ROTATION_VECTOR,
    @SerialName("gyroscope") GYROSCOPE,
}

/** A (wall clock, elapsed realtime) pair, so trace wall-clock times and sensor times share a timeline. */
@Serializable
@SerialName("clock_anchor")
data class ClockAnchorRecord(
    val wallMs: Long,
    val elapsedRealtimeNanos: Long,
    val reason: AnchorReason,
) : CaptureRecord

@Serializable
enum class AnchorReason {
    @SerialName("start") START,
    @SerialName("periodic") PERIODIC,
    @SerialName("end") END,
}

/** Pressed by the person recording when a prompt that should have come did not. */
@Serializable
@SerialName("marker")
data class MarkerRecord(
    val kind: MarkerKind,
    val wallMs: Long,
    val elapsedRealtimeNanos: Long,
    /** The newest frame sequence number the capture had seen when the marker was pressed. */
    val lastFrameSeq: Long? = null,
    val source: MarkerSource,
) : CaptureRecord

@Serializable
enum class MarkerKind {
    @SerialName("missed_alert") MISSED_ALERT,
}

@Serializable
enum class MarkerSource {
    @SerialName("volume_down") VOLUME_DOWN,
    @SerialName("screen_button") SCREEN_BUTTON,
}
