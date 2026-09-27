package com.sailens.shell.capture

import com.sailens.guidance.trace.capture.CaptureSchema
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs one finished capture session into a ZIP under [exportDir] for sharing.
 *
 * The export directory is meant to be `cacheDir/capture_exports/`: it is the only thing exposed to
 * other apps (FileProvider `<cache-path>`), `files/captures/` never is, and `cacheDir` is not backed
 * up. Old exports are cleared at start-up and whenever the capture list opens -- there is no
 * reliable signal for when a share target has finished reading one.
 */
internal class CaptureExporter(
    private val root: File,
    private val exportDir: File,
    /** Space to keep free beyond the ZIP itself. */
    private val freeSpaceMarginBytes: Long = 50L * 1024 * 1024,
) {

    /** Returns the ZIP. Throws [IOException] when the session is missing or space is short. */
    fun export(sessionId: String): File {
        val source = File(root, sessionId)
        if (!File(source, CaptureSchema.MANIFEST_FILE).isFile) throw IOException("no capture '$sessionId'")
        if (!exportDir.isDirectory && !exportDir.mkdirs()) throw IOException("cannot create $exportDir")
        val files = source.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toList()
        val needed = files.sumOf { it.length() } + freeSpaceMarginBytes
        if (exportDir.usableSpace in 1 until needed) {
            throw IOException("not enough free space to export ($needed bytes needed)")
        }

        val zip = File(exportDir, "capture_$sessionId.zip")
        val partial = File(exportDir, "${zip.name}.part")
        ZipOutputStream(partial.outputStream().buffered()).use { out ->
            files.forEach { file ->
                // Images are already JPEG; storing them again saves time without costing size.
                out.putNextEntry(ZipEntry("$sessionId/${file.relativeTo(source).invariantSeparatorsPath}"))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        if (zip.exists()) zip.delete()
        if (!partial.renameTo(zip)) throw IOException("cannot finish $zip")
        return zip
    }

    fun clearExports() {
        exportDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }
}
