package com.sailens.shell.capture

import com.sailens.guidance.trace.capture.CaptureManifest
import com.sailens.guidance.trace.capture.CaptureRecord
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.ClockAnchorRecord
import com.sailens.guidance.trace.capture.FrameRecord
import com.sailens.guidance.trace.capture.MarkerRecord
import com.sailens.guidance.trace.capture.SensorRecord
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes one capture session directory (format: `CaptureSchema`).
 *
 * Not thread-safe: the capture engine calls it from its single writer thread only. Every method may
 * throw [IOException]; the engine turns that into a failed capture, never into a Guidance failure.
 *
 * The manifest is written first, with `complete = false`, and every later version replaces it
 * atomically, so a reader never sees a half-written manifest and a session that was cut short
 * still says so.
 */
internal class CaptureSessionWriter(
    val directory: File,
    initialManifest: CaptureManifest,
) : Closeable {

    var manifest: CaptureManifest = initialManifest
        private set

    /** Approximate size of this session on disk (records are ASCII JSON, so chars ≈ bytes). */
    var bytesWritten: Long = 0
        private set

    private val streams = mutableMapOf<String, BufferedWriter>()
    private var closed = false

    init {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")
        val frames = File(directory, CaptureSchema.FRAMES_DIR)
        if (!frames.isDirectory && !frames.mkdirs()) throw IOException("cannot create $frames")
        writeManifest(initialManifest)
    }

    fun append(record: CaptureRecord) {
        check(!closed) { "writer is closed" }
        val writer = streams.getOrPut(fileFor(record)) {
            File(directory, fileFor(record)).bufferedWriter(bufferSize = STREAM_BUFFER_BYTES)
        }
        val line = CaptureSchema.encodeRecord(record)
        writer.write(line)
        writer.write("\n")
        bytesWritten += line.length + 1
    }

    /** Writes an image under `frames/`; returns its path relative to the session directory. */
    fun writeFrameImage(fileName: String, bytes: ByteArray): String {
        check(!closed) { "writer is closed" }
        val relative = "${CaptureSchema.FRAMES_DIR}/$fileName"
        File(directory, relative).writeBytes(bytes)
        bytesWritten += bytes.size
        return relative
    }

    fun updateManifest(transform: (CaptureManifest) -> CaptureManifest) {
        writeManifest(transform(manifest))
    }

    /** Pushes buffered records to disk, so a later crash loses as little as possible. */
    fun flush() {
        streams.values.forEach { it.flush() }
    }

    /** Flushes and closes the record files, then writes the final manifest. */
    fun finish(transform: (CaptureManifest) -> CaptureManifest) {
        closeStreams()
        writeManifest(transform(manifest))
    }

    override fun close() {
        if (!closed) closeStreams()
    }

    private fun closeStreams() {
        var failure: IOException? = null
        streams.values.forEach { stream ->
            try {
                stream.close()
            } catch (e: IOException) {
                failure = failure ?: e
            }
        }
        streams.clear()
        closed = true
        failure?.let { throw it }
    }

    private fun writeManifest(next: CaptureManifest) {
        writeAtomically(File(directory, CaptureSchema.MANIFEST_FILE), CaptureSchema.encodeManifest(next))
        manifest = next
    }

    private fun fileFor(record: CaptureRecord): String = when (record) {
        is FrameRecord -> CaptureSchema.FRAMES_FILE
        is SensorRecord -> CaptureSchema.SENSORS_FILE
        is ClockAnchorRecord -> CaptureSchema.ANCHORS_FILE
        is MarkerRecord -> CaptureSchema.MARKERS_FILE
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}

/** Replaces [target] with [text] so that readers see either the old or the new file, never a mix. */
internal fun writeAtomically(target: File, text: String) {
    val temp = File(target.parentFile, "${target.name}.tmp")
    temp.writeText(text)
    try {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
