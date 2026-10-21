package com.sailens.guidance.trace.capture

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/** A capture session as read from disk. Records are in file order. */
data class CaptureSession(
    val directory: File,
    val manifest: CaptureManifest,
    val frames: List<FrameRecord>,
    val sensors: List<SensorRecord>,
    val anchors: List<ClockAnchorRecord>,
    val markers: List<MarkerRecord>,
    /** Things a consumer should know about: partial capture, skipped lines, unknown types. */
    val warnings: List<String>,
    /** `model_regression` only: one per processed frame, in processing order. */
    val regressionFrames: List<RegressionFrameRecord> = emptyList(),
    /** `model_regression` only: revoked prompts, in the order they were applied. */
    val revokes: List<RegressionRevokeRecord> = emptyList(),
    /** `model_regression` only: full-resolution event-window frames. */
    val windowFrames: List<WindowFrameRecord> = emptyList(),
    /**
     * `model_regression` only: speech-lifecycle events, in file order. Empty both when nothing was
     * said and when the record keeps no lifecycle -- [RegressionManifest.utteranceLifecycle] tells which.
     */
    val utterances: List<RegressionUtteranceRecord> = emptyList(),
)

sealed interface CaptureReadResult {
    data class Read(val session: CaptureSession) : CaptureReadResult
    data class Rejected(val reason: String) : CaptureReadResult
}

/**
 * Reads a capture directory on the JVM (simulator, replay tests, tooling). It parses records and
 * resolves file paths; it does not decode images.
 *
 * Tolerant where a partial capture is expected -- an interrupted session can end in a torn line,
 * and a newer minor version may add record types -- and strict where the data would be
 * misread: no manifest or an unknown major version is rejected.
 */
object CaptureSessionReader {

    fun read(directory: File): CaptureReadResult {
        val manifestFile = File(directory, CaptureSchema.MANIFEST_FILE)
        if (!manifestFile.isFile) return CaptureReadResult.Rejected("no ${CaptureSchema.MANIFEST_FILE}")

        // The version header is read on its own, before the body: a future major version may have a
        // body this reader cannot decode, and it must be refused as "unsupported", not as "broken".
        val root = try {
            CaptureSchema.json.parseToJsonElement(manifestFile.readText()) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return CaptureReadResult.Rejected("manifest is not a JSON object")
        val major = root.intOrNull(SCHEMA_MAJOR)
            ?: return CaptureReadResult.Rejected("manifest has no integer $SCHEMA_MAJOR")
        val minor = root.intOrNull(SCHEMA_MINOR)
            ?: return CaptureReadResult.Rejected("manifest has no integer $SCHEMA_MINOR")
        if (major != CaptureSchema.MAJOR) {
            return CaptureReadResult.Rejected(
                "schema major $major is not supported (reader supports ${CaptureSchema.MAJOR})",
            )
        }

        val manifest = try {
            CaptureSchema.json.decodeFromJsonElement(CaptureManifest.serializer(), root)
        } catch (e: SerializationException) {
            return CaptureReadResult.Rejected("unreadable manifest: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return CaptureReadResult.Rejected("unreadable manifest: ${e.message}")
        }

        val warnings = mutableListOf<String>()
        if (!manifest.complete) {
            warnings += "capture is incomplete" + (manifest.failureReason?.let { ": $it" } ?: "")
        }
        if (minor > CaptureSchema.MINOR) {
            warnings += "schema minor $minor is newer than the reader's ${CaptureSchema.MINOR}"
        }
        if (manifest.captureMode !in CaptureModes.KNOWN) {
            warnings += "capture mode '${manifest.captureMode}' is not known to this reader"
        }
        if (manifest.captureMode == CaptureModes.MODEL_REGRESSION && manifest.regression == null) {
            warnings += "model_regression capture without a regression block in the manifest"
        }
        manifest.regression?.stats?.firstLostSeq?.let { seq ->
            warnings += "the regression record lost frames from seq $seq on; replay is exact only before it"
        }

        val records = listOf(
            CaptureSchema.FRAMES_FILE,
            CaptureSchema.SENSORS_FILE,
            CaptureSchema.ANCHORS_FILE,
            CaptureSchema.MARKERS_FILE,
            CaptureSchema.REGRESSION_FILE,
            CaptureSchema.WINDOWS_FILE,
        ).flatMap { readRecords(File(directory, it), warnings) }

        return CaptureReadResult.Read(
            CaptureSession(
                directory = directory,
                manifest = manifest,
                frames = records.filterIsInstance<FrameRecord>(),
                sensors = records.filterIsInstance<SensorRecord>(),
                anchors = records.filterIsInstance<ClockAnchorRecord>(),
                markers = records.filterIsInstance<MarkerRecord>(),
                warnings = warnings,
                regressionFrames = records.filterIsInstance<RegressionFrameRecord>(),
                revokes = records.filterIsInstance<RegressionRevokeRecord>(),
                windowFrames = records.filterIsInstance<WindowFrameRecord>(),
                utterances = records.filterIsInstance<RegressionUtteranceRecord>(),
            ),
        )
    }

    /** The stored image of a frame record, relative to the session directory. */
    fun frameFile(session: CaptureSession, frame: FrameRecord): File = File(session.directory, frame.file)

    /** The stored image of an event-window frame. */
    fun windowFile(session: CaptureSession, frame: WindowFrameRecord): File = File(session.directory, frame.file)

    /**
     * The class map a regression frame recorded, checked against its reference (see
     * [ClassMapStore.read]); null when the frame did not run the semantic model.
     *
     * @throws java.io.IOException when the entry is missing or does not match.
     */
    fun classMap(session: CaptureSession, frame: RegressionFrameRecord): IntArray? {
        val ref = frame.semantic?.classMap ?: return null
        return ClassMapStore.read(File(session.directory, CaptureSchema.CLASS_MAPS_FILE), ref, frame.seq)
    }

    private fun readRecords(file: File, warnings: MutableList<String>): List<CaptureRecord> {
        if (!file.isFile) return emptyList()
        val records = mutableListOf<CaptureRecord>()
        val unknownTypes = sortedMapOf<String, Int>()
        file.useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val where = "${file.name}:${index + 1}"
                val obj = try {
                    CaptureSchema.json.parseToJsonElement(line).jsonObject
                } catch (e: SerializationException) {
                    warnings += "$where: skipped unparseable line"
                    return@forEachIndexed
                } catch (e: IllegalArgumentException) {
                    warnings += "$where: skipped a line that is not a JSON object"
                    return@forEachIndexed
                }
                val type = (obj[CaptureSchema.TYPE_KEY] as? JsonPrimitive)?.content
                if (type == null || type !in KNOWN_TYPES) {
                    unknownTypes.merge(type ?: "<none>", 1, Int::plus)
                    return@forEachIndexed
                }
                try {
                    records += CaptureSchema.json.decodeFromJsonElement(CaptureRecord.serializer(), obj)
                } catch (e: SerializationException) {
                    warnings += "$where: skipped invalid '$type' record: ${e.message}"
                } catch (e: IllegalArgumentException) {
                    warnings += "$where: skipped invalid '$type' record: ${e.message}"
                }
            }
        }
        unknownTypes.forEach { (type, count) -> warnings += "${file.name}: ignored $count record(s) of unknown type '$type'" }
        return records
    }

    private val KNOWN_TYPES = setOf(
        "frame", "sensor", "clock_anchor", "marker", "regression_frame", "regression_revoke", "window_frame",
        "regression_utterance",
    )
    private const val SCHEMA_MAJOR = "schemaMajor"
    private const val SCHEMA_MINOR = "schemaMinor"

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
}

/** Throws with the reason when the directory is not a readable capture; for tools and tests. */
fun CaptureReadResult.orThrow(): CaptureSession = when (this) {
    is CaptureReadResult.Read -> session
    is CaptureReadResult.Rejected -> error("capture rejected: $reason")
}
