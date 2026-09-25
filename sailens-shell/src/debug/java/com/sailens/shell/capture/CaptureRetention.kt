package com.sailens.shell.capture

import com.sailens.guidance.trace.capture.CaptureManifest
import com.sailens.guidance.trace.capture.CaptureSchema
import java.io.File

/**
 * Keeps the capture directory bounded (decided 2026-09-25): a session is deleted [maxAgeMs] after it
 * was recorded unless it was exported or pinned, and the total is kept under [maxTotalBytes] by
 * deleting the oldest sessions that are not pinned. The active session is never touched.
 *
 * A directory whose manifest cannot be read is judged by its modification time and treated as not
 * pinned, so a broken capture cannot pin space forever.
 */
internal class CaptureRetention(
    private val root: File,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {

    /** Returns the session ids it deleted. */
    fun prune(nowWallMs: Long, activeSessionId: String? = null): List<String> {
        val sessions = root.listFiles { file -> file.isDirectory }.orEmpty()
            .filter { it.name != activeSessionId }
            .map { describe(it) }
        val deleted = mutableListOf<String>()

        sessions
            .filter { !it.pinned && !it.exported && nowWallMs - it.startedWallMs > maxAgeMs }
            .forEach { if (it.directory.deleteRecursively()) deleted += it.directory.name }

        val remaining = sessions.filter { it.directory.name !in deleted }
        var total = remaining.sumOf { it.bytes } + activeBytes(activeSessionId)
        remaining
            .filter { !it.pinned }
            .sortedBy { it.startedWallMs }
            .forEach { session ->
                if (total <= maxTotalBytes) return@forEach
                if (session.directory.deleteRecursively()) {
                    deleted += session.directory.name
                    total -= session.bytes
                }
            }
        return deleted
    }

    private fun activeBytes(activeSessionId: String?): Long =
        activeSessionId?.let { sizeOf(File(root, it)) } ?: 0L

    private fun describe(directory: File): SessionOnDisk {
        val manifest = readManifest(directory)
        return SessionOnDisk(
            directory = directory,
            startedWallMs = manifest?.startedWallMs ?: directory.lastModified(),
            pinned = manifest?.pinned ?: false,
            exported = manifest?.exportedAtWallMs != null,
            bytes = sizeOf(directory),
        )
    }

    private fun readManifest(directory: File): CaptureManifest? = runCatching {
        CaptureSchema.json.decodeFromString(
            CaptureManifest.serializer(),
            File(directory, CaptureSchema.MANIFEST_FILE).readText(),
        )
    }.getOrNull()

    private fun sizeOf(directory: File): Long =
        directory.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private class SessionOnDisk(
        val directory: File,
        val startedWallMs: Long,
        val pinned: Boolean,
        val exported: Boolean,
        val bytes: Long,
    )

    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 2L * 1024 * 1024 * 1024
    }
}
