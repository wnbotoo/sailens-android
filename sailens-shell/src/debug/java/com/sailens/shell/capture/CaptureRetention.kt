package com.sailens.shell.capture

import com.sailens.guidance.trace.capture.CaptureManifest
import com.sailens.guidance.trace.capture.CaptureSchema
import java.io.File

/**
 * Keeps the capture directory bounded (decided 2026-09-25): a session expires [maxAgeMs] after it
 * was recorded unless it was exported or pinned, and the total is kept under [maxTotalBytes] by
 * deleting the oldest sessions that are not pinned. The active session is never deleted here; the
 * capture engine ends it instead when it alone would exceed the cap.
 *
 * Retention runs at maintenance opportunities (debug app start, before a capture, when the capture
 * list opens, periodically during a capture), so an expired session is deleted at the next one --
 * whether or not capture is switched on.
 *
 * A directory whose manifest cannot be read is judged by its modification time and treated as not
 * pinned, so a broken capture cannot pin space forever.
 */
internal class CaptureRetention(
    private val root: File,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {

    /**
     * @param activeBytes the active session's current size, counted towards the cap.
     * @return what it deleted and how many bytes the other (non-active) sessions still hold.
     */
    fun prune(nowWallMs: Long, activeSessionId: String? = null, activeBytes: Long = 0): Result {
        val sessions = root.listFiles { file -> file.isDirectory }.orEmpty()
            .filter { it.name != activeSessionId }
            .map { describe(it) }
        val deleted = mutableListOf<String>()

        sessions
            .filter { !it.pinned && !it.exported && nowWallMs - it.startedWallMs > maxAgeMs }
            .forEach { if (it.directory.deleteRecursively()) deleted += it.directory.name }

        val remaining = sessions.filter { it.directory.name !in deleted }
        var others = remaining.sumOf { it.bytes }
        remaining
            .filter { !it.pinned }
            .sortedBy { it.startedWallMs }
            .forEach { session ->
                if (others + activeBytes <= maxTotalBytes) return@forEach
                if (session.directory.deleteRecursively()) {
                    deleted += session.directory.name
                    others -= session.bytes
                }
            }
        return Result(deleted = deleted, otherSessionsBytes = others)
    }

    data class Result(val deleted: List<String>, val otherSessionsBytes: Long)

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
