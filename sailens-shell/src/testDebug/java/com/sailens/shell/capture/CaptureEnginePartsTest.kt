package com.sailens.shell.capture

import com.sailens.guidance.trace.capture.AnchorReason
import com.sailens.guidance.trace.capture.CaptureManifest
import com.sailens.guidance.trace.capture.CaptureModes
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.ClockAnchorRecord
import com.sailens.guidance.trace.capture.orThrow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CaptureEnginePartsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manifest(id: String, startedWallMs: Long = 1_000, pinned: Boolean = false, exported: Boolean = false) =
        CaptureManifest(
            sessionId = id,
            captureMode = CaptureModes.FIELD_EVIDENCE,
            startedWallMs = startedWallMs,
            startedElapsedRealtimeNanos = 0,
            deviceManufacturer = "Acme",
            deviceModel = "Phone",
            sdkInt = 35,
            pinned = pinned,
            exportedAtWallMs = if (exported) startedWallMs else null,
        )

    // ---- writer --------------------------------------------------------------------------------

    @Test
    fun `the manifest exists and says incomplete before anything else is written`() {
        val dir = File(tmp.root, "s1")
        CaptureSessionWriter(dir, manifest("s1")).use { }

        val read = CaptureSessionReader.read(dir).orThrow()
        assertFalse(read.manifest.complete)
    }

    @Test
    fun `records go to their own files and a finished session reads back complete`() {
        val dir = File(tmp.root, "s1")
        val writer = CaptureSessionWriter(dir, manifest("s1"))
        writer.append(ClockAnchorRecord(1, 2, AnchorReason.START))
        writer.writeFrameImage("000001.jpg", byteArrayOf(1, 2, 3))
        writer.finish { it.copy(complete = true) }

        val read = CaptureSessionReader.read(dir).orThrow()
        assertTrue(read.manifest.complete)
        assertEquals(1, read.anchors.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir, "frames/000001.jpg").readBytes())
        assertFalse("no temp file is left behind", File(dir, "${CaptureSchema.MANIFEST_FILE}.tmp").exists())
    }

    // ---- downscaler ----------------------------------------------------------------------------

    @Test
    fun `target size keeps the aspect ratio, fits the long side and is even`() {
        assertEquals(640 to 360, Nv21Downscaler.targetSize(960, 540, 640))
        assertEquals(360 to 640, Nv21Downscaler.targetSize(540, 960, 640))
        assertEquals(8 to 6, Nv21Downscaler.targetSize(8, 6, 640)) // never upscales
        assertEquals(640 to 480, Nv21Downscaler.targetSize(4000, 3001, 640))
    }

    @Test
    fun `downscaling samples luma and interleaves V then U, honouring row and pixel strides`() {
        val frame = yuvFrame(seq = 1, width = 8, height = 6, rowPadding = 4)

        val image = Nv21Downscaler.downscale(frame, maxLongSide = 4)!!

        assertEquals(4, image.width)
        assertEquals(2, image.height) // 8x6 -> 4x3 -> even height 2
        // Luma: row 0 samples source rows 0, cols 0,2,4,6 -> x + 10*y.
        assertEquals(listOf(0, 2, 4, 6), (0 until 4).map { image.bytes[it].toInt() })
        // Chroma row 0: pairs (V, U) for chroma cols 0,1 sampled from source chroma cols 0,2.
        val chroma = image.bytes.drop(4 * 2).map { it.toInt() and 0xFF }
        assertEquals(listOf(200, 100, 200, 102), chroma)
    }

    // ---- retention -----------------------------------------------------------------------------

    private fun session(id: String, startedWallMs: Long, bytes: Int = 10, pinned: Boolean = false, exported: Boolean = false) {
        CaptureSessionWriter(File(tmp.root, id), manifest(id, startedWallMs, pinned, exported)).use {
            it.writeFrameImage("000001.jpg", ByteArray(bytes))
        }
    }

    @Test
    fun `sessions older than the limit go unless pinned or exported, and the active one is never touched`() {
        val day = 24L * 60 * 60 * 1000
        val now = 30 * day
        session("old", now - 8 * day)
        session("old-pinned", now - 8 * day, pinned = true)
        session("old-exported", now - 8 * day, exported = true)
        session("recent", now - 1 * day)
        session("active", now - 9 * day)

        val deleted = CaptureRetention(tmp.root).prune(now, activeSessionId = "active").deleted

        assertEquals(listOf("old"), deleted)
        assertEquals(setOf("old-pinned", "old-exported", "recent", "active"), tmp.root.list()!!.toSet())
    }

    @Test
    fun `over the size cap the oldest unpinned sessions go first`() {
        session("a", startedWallMs = 1, bytes = 1_000)
        session("b", startedWallMs = 2, bytes = 1_000, pinned = true)
        session("c", startedWallMs = 3, bytes = 1_000)
        session("d", startedWallMs = 4, bytes = 1_000)

        val manifestBytes = File(tmp.root, "a").walk().filter { it.isFile }.sumOf { it.length() } - 1_000
        val cap = 2 * (1_000 + manifestBytes) + 10
        val deleted = CaptureRetention(tmp.root, maxAgeMs = Long.MAX_VALUE, maxTotalBytes = cap).prune(nowWallMs = 5).deleted

        assertEquals(listOf("a", "c"), deleted)
        assertEquals(setOf("b", "d"), tmp.root.list()!!.toSet())
    }

    @Test
    fun `a directory without a readable manifest is judged by its age and never pins space`() {
        val broken = File(tmp.root, "broken").apply { mkdirs() }
        File(broken, "manifest.json").writeText("{ not json")
        broken.setLastModified(0)

        val deleted = CaptureRetention(tmp.root, maxAgeMs = 1_000).prune(nowWallMs = 1_000_000).deleted

        assertEquals(listOf("broken"), deleted)
    }
}
