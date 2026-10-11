package com.sailens.guidance.trace.capture

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The model-regression record (M0b, docs/local-navigation-implementation.md §4 "M0b").
 *
 * Goal: replay the decision path exactly on the JVM. The record therefore holds every input of that
 * path the JVM cannot compute the same way itself -- the model outputs and the native kernels'
 * outputs, the injected clocks' readings, the motion state, the frame-quality measurements and the
 * revocations that arrive from another thread -- plus the decisions to compare against. Everything
 * else (analysis, event generation, cooldown) is real code at replay time.
 *
 * Floats are written with Kotlin's shortest round-trip representation, so a value read back is the
 * same `Float` bit for bit.
 */

/** In the manifest of a `model_regression` capture. */
@Serializable
data class RegressionManifest(
    val recorderVersion: Int = RECORDER_VERSION,
    /** How `classmaps.bin` stores a class map ([ClassMapStore.ENCODING]). */
    val classMapEncoding: String = ClassMapStore.ENCODING,
    /**
     * The configs the decision path ran with, as written by their serializers. Kept as raw JSON so
     * a reader never depends on today's config classes; replay decodes them leniently.
     */
    val perceptionConfig: JsonElement? = null,
    val analysisConfig: JsonElement? = null,
    /** `NavigationSemantics.taxonomyId` the class ids mean. */
    val taxonomyId: String? = null,
    /** What the distance estimate came from ("position" without hardware depth). */
    val depthSource: String? = null,
    val stats: RegressionStats = RegressionStats(),
) {
    companion object {
        const val RECORDER_VERSION: Int = 1
    }
}

/**
 * The record's own counters. A replay is exact only over an unbroken run of frames: stateful
 * smoothers and trackers cannot be resumed after a gap, so the first lost frame ends exactness.
 */
@Serializable
data class RegressionStats(
    /** Frames whose [RegressionFrameRecord] was written. */
    val framesRecorded: Long = 0,
    /** Frames Guidance processed but the record could not keep (queue full, write failure). */
    val framesLost: Long = 0,
    /** Sequence number of the first lost frame; replay is exact only before it. Null when none was. */
    val firstLostSeq: Long? = null,
    val classMapBytes: Long = 0,
    val windowsRecorded: Long = 0,
    val windowFramesStored: Long = 0,
    val windowFramesDropped: Long = 0,
)

/**
 * Everything one processed frame contributed to the decision path, and what it decided.
 *
 * @param clocks every value the injected clocks returned while this frame was processed, per
 *   component ([RegressionClocks]), in call order. Replay serves them back in the same order and
 *   checks that each list is used up exactly.
 * @param semantic what the semantic stage did; null if the frame failed before it.
 * @param detection the detector's raw output, present only when the detector ran on this frame.
 * @param connectivity each call to the connectivity statistics, in order.
 * @param stationary `DeviceSensorRepository.isStationary` as the decision step read it.
 * @param events the decided events, highest priority first (the first is the one offered).
 * @param failure set when processing the frame failed; the frame then decided nothing.
 */
@Serializable
@SerialName("regression_frame")
data class RegressionFrameRecord(
    val seq: Long,
    val sensorTimestampNanos: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val clocks: Map<String, List<Long>> = emptyMap(),
    val semantic: SemanticRunRecord? = null,
    val detection: DetectionRunRecord? = null,
    val connectivity: List<ConnectivityRecord> = emptyList(),
    val quality: FrameQualityRecord? = null,
    val stationary: Boolean? = null,
    val events: List<EventRecord> = emptyList(),
    val failure: String? = null,
) : CaptureRecord

/** Clock components ([RegressionFrameRecord.clocks] keys): one per injected clock. */
object RegressionClocks {
    /** `ProcessFrameUseCase`'s clock: scheduling, tracker time, timing. */
    const val PERCEPTION: String = "perception"

    /** `GroundRecognitionAnalyzer`'s clock. */
    const val GROUND: String = "ground"

    /** `DecideEventsUseCase`'s clock: event times, expiry, cooldown. */
    const val DECIDE: String = "decide"
}

/**
 * @param run [SemanticRuns]: the model ran, the cached analysis was reused, or the run failed.
 * @param classMap where this frame's class map is in `classmaps.bin`; present when [run] is "ran".
 * @param stats the fused native pass's statistics; null when it did not run (the Kotlin path then
 *   derives them from the class map, which replay can do too).
 */
@Serializable
data class SemanticRunRecord(
    val run: String,
    val classMap: ClassMapRef? = null,
    val stats: SemanticStatsRecord? = null,
    val failure: String? = null,
)

object SemanticRuns {
    const val RAN: String = "ran"
    const val CACHED: String = "cached"
    const val FAILED: String = "failed"
}

/** A class map in `classmaps.bin`: [offset] and [length] of its whole entry, as [ClassMapStore] wrote it. */
@Serializable
data class ClassMapRef(
    val offset: Long,
    val length: Int,
    val width: Int,
    val height: Int,
    /** CRC32 of the uncompressed class ids, one byte each. */
    val crc32: Long,
)

/**
 * `SegmentationAnalysisStats` from the native pass. Its two masks are not stored: they are lookups
 * of the class map through `NavigationSemantics`, so replay rebuilds them and checks the CRCs, which
 * proves the rebuild is the mask the device used.
 */
@Serializable
data class SemanticStatsRecord(
    val passableMaskCrc32: Long,
    val obstacleMaskCrc32: Long,
    val roadRatio: Float,
    val hasTrafficLight: Boolean,
    /** `GroundType` name -> fraction. */
    val bottomCenterGroundDistribution: Map<String, Float>,
    val bottomCenterRoadRatio: Float,
    val bottomStats: BottomStatsRecord,
    val passablePixelCount: Int,
    val navigationPassableRatio: Float,
    val obstaclePixelCount: Int,
    val classCounts: List<Int>,
)

@Serializable
data class BottomStatsRecord(
    val coverage: Float,
    val maxRunWidth: Int,
    val maxRunWidthRatio: Float,
    val maxRunRow: Int,
    val maxRunStart: Int,
    val maxRunEnd: Int,
    val maxRunCenter: Float,
)

/** The detector's output on a frame it ran on, before Guidance's extraction and tracking. */
@Serializable
data class DetectionRunRecord(
    val detections: List<DetectionItem> = emptyList(),
    val failure: String? = null,
)

@Serializable
data class DetectionItem(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * One call to the connectivity statistics. [inputCrc32] identifies the passable mask it was given:
 * when replay builds the same mask, the recorded [stats] are used (the device ran a native kernel the
 * JVM's Kotlin fallback does not match); when replay builds a different one, upstream code changed
 * and replay must compute instead.
 */
@Serializable
data class ConnectivityRecord(
    val inputCrc32: Long,
    val width: Int,
    val height: Int,
    /** Null when the native kernel returned nothing and the device fell back to Kotlin itself. */
    val stats: ConnectivityStatsRecord? = null,
)

@Serializable
data class ConnectivityStatsRecord(
    val validLayers: Int,
    val totalLayers: Int,
    val widthRetentionAvg: Float,
    val widthRetentionP25: Float,
    val widthSlope: Float,
    val floodReachRatio: Float,
    val floodWidthRetentionP25: Float,
    val floodVisitedRatio: Float,
    val suggestedBias: String? = null,
)

/**
 * `FrameQualityAnalyzer`'s measurement and per-frame classification (before debouncing). The
 * measurements are null when the frame could not be sampled; classification is then OK.
 * [sharpness] is recorded for calibration only; no decision uses it yet.
 */
@Serializable
data class FrameQualityRecord(
    val meanLuma: Float? = null,
    val stdLuma: Float? = null,
    val sharpness: Float? = null,
    val classified: String,
)

/** A decided `SceneEvent`, every field; enums by name, sets as sorted lists. */
@Serializable
data class EventRecord(
    val id: String,
    val timestamp: Long,
    val category: String,
    val priority: String,
    val messageKey: String,
    val messageParams: Map<String, String> = emptyMap(),
    val expiresAt: Long,
    val dedupeKey: String,
    val cooldownKeys: List<String> = emptyList(),
    val cooldownGroups: List<List<String>> = emptyList(),
    val confidence: Float,
    val severity: String,
    val relatedZones: List<String> = emptyList(),
    val distance: String? = null,
    val directionHint: String? = null,
)

/**
 * A prompt the output side could not deliver, whose cooldown entry was therefore revoked. It arrives
 * from the UI thread, between decisions; [decisionsBefore] is how many decisions the cooldown had
 * made when it applied the revoke, so replay applies it before decision number [decisionsBefore].
 */
@Serializable
@SerialName("regression_revoke")
data class RegressionRevokeRecord(
    val eventId: String,
    val decisionsBefore: Long,
    val elapsedRealtimeNanos: Long,
) : CaptureRecord

/**
 * A full-resolution frame kept around a flagged moment, for re-running models offline. Encoded as
 * JPEG, so it is close to but not exactly the model's input; old and new models compared on these
 * frames see the same pixels.
 */
@Serializable
@SerialName("window_frame")
data class WindowFrameRecord(
    val seq: Long,
    val sensorTimestampNanos: Long,
    /** [WindowTriggers]. */
    val trigger: String,
    /** What triggered it: the delivered event's id, or the marker's elapsed time. */
    val triggerRef: String? = null,
    val encoding: FrameEncoding,
    /** Path relative to the session directory. */
    val file: String,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
) : CaptureRecord

object WindowTriggers {
    const val DELIVERED_PROMPT: String = "delivered_prompt"
    const val MARKER: String = "marker"
}
