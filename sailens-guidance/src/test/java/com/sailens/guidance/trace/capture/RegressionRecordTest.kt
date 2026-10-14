package com.sailens.guidance.trace.capture

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class RegressionRecordTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun classMap(width: Int, height: Int, seed: Int) = IntArray(width * height) { (it / 7 + seed) % 19 }

    private fun frameRecord(seq: Long, ref: ClassMapRef?) = RegressionFrameRecord(
        seq = seq,
        sensorTimestampNanos = 9_000_000_000 + seq * 33_000_000,
        width = 1280,
        height = 720,
        rotationDegrees = 90,
        clocks = mapOf(
            RegressionClocks.PERCEPTION to listOf(1000L, 1001L, 1018L, 1031L),
            RegressionClocks.GROUND to listOf(1030L),
            RegressionClocks.DECIDE to listOf(1031L),
        ),
        semantic = SemanticRunRecord(
            run = if (ref != null) SemanticRuns.RAN else SemanticRuns.CACHED,
            classMap = ref,
            stats = ref?.let {
                SemanticStatsRecord(
                    passableMaskCrc32 = 1, obstacleMaskCrc32 = 2, roadRatio = 0.1f, hasTrafficLight = false,
                    bottomCenterGroundDistribution = mapOf("SIDEWALK" to 0.7f, "ROAD" to 0.2f), bottomCenterRoadRatio = 0.2f,
                    bottomStats = BottomStatsRecord(0.6f, 120, 0.33f, 600, 100, 220, 0.45f),
                    passablePixelCount = 9000, navigationPassableRatio = 0.4f, obstaclePixelCount = 300,
                    classCounts = List(19) { i -> i * 10 },
                )
            },
        ),
        detection = DetectionRunRecord(listOf(DetectionItem(56, "chair", 0.92f, 0.3f, 0.4f, 0.2f, 0.5f))),
        connectivity = listOf(
            ConnectivityRecord(
                inputCrc32 = 77, width = 360, height = 640,
                stats = ConnectivityStatsRecord(5, 6, 0.8f, 0.6f, -0.1f, 0.9f, 0.55f, 0.4f, suggestedBias = null),
            ),
        ),
        quality = FrameQualityRecord(meanLuma = 120.5f, stdLuma = 40.25f, sharpness = 33.1f, classified = "OK"),
        stationary = false,
        events = listOf(
            EventRecord(
                id = "6f1c1f9e-3c7a-4d0e-9c2b-2f5b1f3b8a10", timestamp = 1031, category = "OBSTACLE", priority = "HIGH",
                messageKey = "event_obstacle_center_static", expiresAt = 6031, dedupeKey = "obstacle_center",
                cooldownKeys = listOf("obstacle_PRIMARY_CENTER"), confidence = 0.92f, severity = "MODERATE",
                relatedZones = listOf("CENTER"), distance = "MEDIUM",
            ),
        ),
    )

    @Test
    fun `a model regression capture reads back with its records and class maps`() {
        val dir = tmp.newFolder("s-reg")
        val maps = File(dir, CaptureSchema.CLASS_MAPS_FILE)
        val refs = mutableListOf<ClassMapRef>()
        RandomAccessFile(maps, "rw").use { out ->
            for (seq in 1L..2L) {
                val entry = ClassMapStore.encode(seq, 36, 64, classMap(36, 64, seq.toInt()))
                refs += entry.ref(out.length())
                out.seek(out.length())
                out.write(entry.bytes)
            }
        }
        val frames = listOf(frameRecord(1, refs[0]), frameRecord(2, refs[1]), frameRecord(3, null))
        val revoke = RegressionRevokeRecord("6f1c1f9e-3c7a-4d0e-9c2b-2f5b1f3b8a10", decisionsBefore = 2, elapsedRealtimeNanos = 9_100_000_000)
        val window = WindowFrameRecord(2, 9_066_000_000, WindowTriggers.DELIVERED_PROMPT, "6f1c1f9e", FrameEncoding.JPEG, "windows/000002.jpg", 1280, 720, 90)
        File(dir, CaptureSchema.REGRESSION_FILE).writeText(
            (frames.map(CaptureSchema::encodeRecord) + CaptureSchema.encodeRecord(revoke)).joinToString("\n", postfix = "\n"),
        )
        File(dir, CaptureSchema.WINDOWS_FILE).writeText(CaptureSchema.encodeRecord(window) + "\n")
        File(dir, CaptureSchema.MANIFEST_FILE).writeText(
            CaptureSchema.encodeManifest(
                CaptureManifest(
                    sessionId = "s-reg", captureMode = CaptureModes.MODEL_REGRESSION, startedWallMs = 1, startedElapsedRealtimeNanos = 2,
                    deviceManufacturer = "Acme", deviceModel = "Phone", sdkInt = 36, complete = true,
                    group = CaptureGroups.OUTDOOR, sceneLabel = "granite sidewalk",
                    regression = RegressionManifest(
                        perceptionConfig = CaptureSchema.json.encodeToJsonElement(PerceptionConfig.serializer(), PerceptionConfig()),
                        analysisConfig = CaptureSchema.json.encodeToJsonElement(AnalysisConfig.serializer(), AnalysisConfig()),
                        taxonomyId = "cityscapes-19", depthSource = "position",
                        stats = RegressionStats(framesRecorded = 3, classMapBytes = maps.length()),
                    ),
                ),
            ),
        )

        val session = CaptureSessionReader.read(dir).orThrow()
        assertEquals(emptyList<String>(), session.warnings)
        assertEquals(frames, session.regressionFrames)
        assertEquals(listOf(revoke), session.revokes)
        assertEquals(listOf(window), session.windowFrames)
        assertEquals(CaptureGroups.OUTDOOR, session.manifest.group)
        assertArrayEquals(classMap(36, 64, 2), CaptureSessionReader.classMap(session, session.regressionFrames[1]))
        assertNull(CaptureSessionReader.classMap(session, session.regressionFrames[2]))
        // The recorded configs decode back to the configs that ran.
        assertEquals(
            PerceptionConfig(),
            CaptureSchema.json.decodeFromJsonElement(PerceptionConfig.serializer(), session.manifest.regression!!.perceptionConfig!!),
        )
    }

    @Test
    fun `a class map entry that is not the one recorded is refused`() {
        val file = tmp.newFile("classmaps.bin")
        val entry = ClassMapStore.encode(5, 8, 4, classMap(8, 4, 0))
        file.writeBytes(entry.bytes)
        val ref = entry.ref(0)
        assertArrayEquals(classMap(8, 4, 0), ClassMapStore.read(file, ref, 5))

        expectIo { ClassMapStore.read(file, ref, 6) } // another frame's entry
        expectIo { ClassMapStore.read(file, ref.copy(crc32 = ref.crc32 xor 1), 5) }
        expectIo { ClassMapStore.read(file, ref.copy(offset = 3), 5) } // not an entry start
        val corrupt = entry.bytes.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        file.writeBytes(corrupt)
        expectIo { ClassMapStore.read(file, ref, 5) }
        file.writeBytes(entry.bytes.copyOf(entry.bytes.size - 4)) // torn
        expectIo { ClassMapStore.read(file, ref, 5) }
    }

    @Test
    fun `a reused encoder writes the same entries as fresh ones`() {
        ClassMapStore.Encoder().use { encoder ->
            for (seq in 1L..3L) {
                val ids = classMap(20 + seq.toInt(), 10, seq.toInt())
                assertArrayEquals(ClassMapStore.encode(seq, 20 + seq.toInt(), 10, ids).bytes, encoder.encode(seq, 20 + seq.toInt(), 10, ids).bytes)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a class id that does not fit a byte is refused, not truncated`() {
        ClassMapStore.encode(1, 2, 1, intArrayOf(3, 300))
    }

    @Test
    fun `floats round-trip bit for bit`() {
        val tricky = listOf(0.1f, 1.0E-7f, Float.MIN_VALUE, 0.33333334f, 123456.79f, -0.0f)
        tricky.forEach { value ->
            val record = FrameQualityRecord(meanLuma = value, classified = "OK")
            val back = CaptureSchema.json.decodeFromString(FrameQualityRecord.serializer(), CaptureSchema.json.encodeToString(FrameQualityRecord.serializer(), record))
            assertEquals(value.toRawBits(), back.meanLuma!!.toRawBits())
        }
    }

    @Test
    fun `recorded configs decode leniently when a field was added since`() {
        val recorded = CaptureSchema.json.encodeToJsonElement(PerceptionConfig.serializer(), PerceptionConfig(maxObstacles = 7)).jsonObject
        val fromNewerBuild = JsonObject(recorded + ("someFutureField" to JsonPrimitive(3)))
        val back = CaptureSchema.json.decodeFromJsonElement(PerceptionConfig.serializer(), fromNewerBuild)
        assertEquals(7, back.maxObstacles)
        assertTrue(Json.encodeToString(AnalysisConfig.serializer(), AnalysisConfig()).isNotEmpty())
    }

    private fun expectIo(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (expected: IOException) {
        }
    }
}
