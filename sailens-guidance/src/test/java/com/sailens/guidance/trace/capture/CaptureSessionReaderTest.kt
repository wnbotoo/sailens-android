package com.sailens.guidance.trace.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CaptureSessionReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val manifest = CaptureManifest(
        sessionId = "s-1",
        captureMode = CaptureModes.FIELD_EVIDENCE,
        startedWallMs = 1_700_000_000_000,
        startedElapsedRealtimeNanos = 9_000_000_000,
        deviceManufacturer = "Acme",
        deviceModel = "Phone",
        sdkInt = 35,
        camera = CaptureCameraRecord(
            cameraId = "0",
            capturedAtElapsedRealtimeNanos = 9_000_000_000,
            sensorOrientationDegrees = 90,
            timestampSource = "realtime",
            lensIntrinsicCalibration = listOf(1500f, 1500f, 2000f, 1500f, 0f),
            activeArray = listOf(0, 0, 4000, 3000),
            availableFocalLengthsMm = listOf(6.9f),
        ),
        complete = true,
    )

    private val frame = FrameRecord(
        seq = 12,
        sensorTimestampNanos = 9_100_000_000,
        receivedElapsedRealtimeNanos = 9_103_000_000,
        sourceWidth = 960,
        sourceHeight = 540,
        rotationDegrees = 90,
        encoding = FrameEncoding.JPEG,
        file = "frames/000012.jpg",
        width = 640,
        height = 360,
        sensorToBufferTransform = listOf(0.24f, 0f, 0f, 0f, 0.24f, -60f, 0f, 0f, 1f),
        cropRect = listOf(0, 0, 960, 540),
    )
    private val sensor = SensorRecord(CaptureSensor.GRAVITY, 9_101_000_000, 3, listOf(0f, 9.8f, 0.1f))
    private val anchor = ClockAnchorRecord(1_700_000_000_010, 9_010_000_000, AnchorReason.START)
    private val marker = MarkerRecord(
        MarkerKind.MISSED_ALERT, 1_700_000_005_000, 14_000_000_000, lastFrameSeq = 12, source = MarkerSource.VOLUME_DOWN,
    )

    private fun session(
        manifestJson: String = CaptureSchema.encodeManifest(manifest),
        frames: List<String> = listOf(CaptureSchema.encodeRecord(frame)),
        sensors: List<String> = listOf(CaptureSchema.encodeRecord(sensor)),
    ): File {
        val dir = tmp.newFolder()
        File(dir, CaptureSchema.MANIFEST_FILE).writeText(manifestJson)
        File(dir, CaptureSchema.FRAMES_FILE).writeText(frames.joinToString("\n", postfix = "\n"))
        File(dir, CaptureSchema.SENSORS_FILE).writeText(sensors.joinToString("\n", postfix = "\n"))
        File(dir, CaptureSchema.ANCHORS_FILE).writeText(CaptureSchema.encodeRecord(anchor) + "\n")
        File(dir, CaptureSchema.MARKERS_FILE).writeText(CaptureSchema.encodeRecord(marker) + "\n")
        return dir
    }

    @Test
    fun `everything written round-trips`() {
        val read = CaptureSessionReader.read(session()).orThrow()

        assertEquals(manifest, read.manifest)
        assertEquals(listOf(frame), read.frames)
        assertEquals(listOf(sensor), read.sensors)
        assertEquals(listOf(anchor), read.anchors)
        assertEquals(listOf(marker), read.markers)
        assertTrue(read.warnings.toString(), read.warnings.isEmpty())
    }

    @Test
    fun `every record line carries its type`() {
        assertTrue(CaptureSchema.encodeRecord(frame).contains("\"type\":\"frame\""))
        assertTrue(CaptureSchema.encodeRecord(sensor).contains("\"type\":\"sensor\""))
        assertTrue(CaptureSchema.encodeRecord(anchor).contains("\"type\":\"clock_anchor\""))
        assertTrue(CaptureSchema.encodeRecord(marker).contains("\"type\":\"marker\""))
    }

    @Test
    fun `a missing manifest is rejected`() {
        val dir = tmp.newFolder()

        assertTrue(CaptureSessionReader.read(dir) is CaptureReadResult.Rejected)
    }

    @Test
    fun `an unknown major version is rejected rather than misread`() {
        val future = CaptureSchema.encodeManifest(manifest.copy(schemaMajor = CaptureSchema.MAJOR + 1))

        val result = CaptureSessionReader.read(session(manifestJson = future))

        assertTrue(result is CaptureReadResult.Rejected)
    }

    @Test
    fun `a newer minor version with unknown fields and record types is read with warnings`() {
        val newerManifest = CaptureSchema.encodeManifest(manifest.copy(schemaMinor = CaptureSchema.MINOR + 1))
            .replaceFirst("{", "{\"futureField\":42,")
        val newerFrame = CaptureSchema.encodeRecord(frame).replaceFirst("{", "{\"exposureNs\":1000,")
        val unknownRecord = """{"type":"depth_tile","seq":12}"""

        val read = CaptureSessionReader.read(
            session(manifestJson = newerManifest, frames = listOf(newerFrame, unknownRecord)),
        ).orThrow()

        assertEquals(listOf(frame), read.frames)
        assertTrue(read.warnings.any { "minor" in it })
        assertTrue(read.warnings.any { "unknown type 'depth_tile'" in it })
    }

    @Test
    fun `a newer minor version with a capture mode this reader does not know is read, not rejected`() {
        // M0b adds "model_regression" in a minor bump; an older reader must carry it through.
        val newer = CaptureSchema.encodeManifest(
            manifest.copy(schemaMinor = CaptureSchema.MINOR + 1, captureMode = "model_regression"),
        )

        val read = CaptureSessionReader.read(session(manifestJson = newer)).orThrow()

        assertEquals("model_regression", read.manifest.captureMode)
        assertTrue(read.warnings.any { "capture mode 'model_regression'" in it })
    }

    @Test
    fun `a manifest without explicit version numbers is rejected, not assumed current`() {
        val unversioned = CaptureSchema.encodeManifest(manifest)
            .replace(Regex("\"schemaMajor\":\\d+,"), "")
            .replace(Regex("\"schemaMinor\":\\d+,"), "")
        val noMinor = CaptureSchema.encodeManifest(manifest).replace(Regex("\"schemaMinor\":\\d+,"), "")

        assertTrue(CaptureSessionReader.read(session(manifestJson = unversioned)) is CaptureReadResult.Rejected)
        assertTrue(CaptureSessionReader.read(session(manifestJson = noMinor)) is CaptureReadResult.Rejected)
    }

    @Test
    fun `a future major version with an incompatible body is refused as unsupported`() {
        // Nothing of the current body survives; the version gate must decide before decoding it.
        val future = """{"schemaMajor":${CaptureSchema.MAJOR + 1},"schemaMinor":0,"session":{"id":7}}"""

        val result = CaptureSessionReader.read(session(manifestJson = future))

        assertTrue(result is CaptureReadResult.Rejected)
        assertTrue((result as CaptureReadResult.Rejected).reason, "not supported" in result.reason)
    }

    @Test
    fun `a manifest that is not a JSON object is rejected`() {
        assertTrue(CaptureSessionReader.read(session(manifestJson = "[1,2]")) is CaptureReadResult.Rejected)
    }

    @Test
    fun `an interrupted capture keeps what was written and says it is incomplete`() {
        val interrupted = CaptureSchema.encodeManifest(manifest.copy(complete = false))
        val torn = CaptureSchema.encodeRecord(sensor).take(20)

        val read = CaptureSessionReader.read(
            session(manifestJson = interrupted, sensors = listOf(CaptureSchema.encodeRecord(sensor), torn)),
        ).orThrow()

        assertFalse(read.manifest.complete)
        assertEquals(listOf(sensor), read.sensors)
        assertTrue(read.warnings.any { "incomplete" in it })
        assertTrue(read.warnings.any { "${CaptureSchema.SENSORS_FILE}:2" in it })
    }

    @Test
    fun `frame files resolve relative to the session directory`() {
        val dir = session()
        val read = CaptureSessionReader.read(dir).orThrow()

        assertEquals(File(dir, "frames/000012.jpg"), CaptureSessionReader.frameFile(read, read.frames.single()))
    }
}
