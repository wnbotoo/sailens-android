package com.sailens.shell.capture

import com.sailens.camera.CameraCharacteristicsProvider
import com.sailens.core.frame.ImageFrame
import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.guidance.depth.geometry.DepthEvidenceRun
import com.sailens.guidance.depth.geometry.DepthEvidenceSlot
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.SupportGuardRule
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.model.trace.DepthRunTrace
import com.sailens.guidance.trace.capture.CaptureModes
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.DepthEvidenceFiles
import com.sailens.guidance.trace.capture.orThrow
import com.sailens.guidance.trace.regression.DepthEvidenceReplay
import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DepthRun
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.ResizePolicy
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The depth evidence stream (docs/field-capture.md §11): what a run's record holds once written and read back,
 * that the copy is the frame's own bytes at the time, and that every run it cannot keep is counted.
 */
class DepthEvidenceCaptureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val writer = Executors.newSingleThreadExecutor()
    private val log = RecordingLog()

    @After
    fun tearDown() {
        writer.shutdownNow()
    }

    private fun stream(poolSize: Int = 4, queueCapacity: Int = 4) =
        DepthEvidenceCapture(log, writer.asCoroutineDispatcher(), DepthEvidenceCaptureConfig(poolSize, queueCapacity))

    private fun controller(evidence: DepthEvidenceCapture, mode: String = CaptureModes.FIELD_EVIDENCE, selected: Boolean = true) =
        FieldCaptureController(
            root = tmp.root,
            sessionMode = { mode },
            frameSource = FakeFrameSource(),
            cameraFacts = CameraCharacteristicsProvider { null },
            sensors = FakeSensors(),
            encoder = JpegEncoder { image, _ -> ByteArray(image.width) },
            clock = FakeClock(),
            deviceInfo = TEST_DEVICE,
            log = log,
            depthEvidence = evidence,
            depthEvidenceSelected = { selected },
        )

    // yuvFrame is 8x6 rotated by 90: the analysis image is 6x8.
    private val transform = DepthInputTransform.create(6, 8, 9, 16, ResizePolicy.PAD_KEEP_ASPECT)
    private val intrinsics = CameraIntrinsics(5f, 5f, 3f, 4f, 6, 8, IntrinsicsSource.CALIBRATION)
    private val down = Vec3(0f, 0.9f, 0.43f)

    private fun geometry(frame: ImageFrame) = CameraGeometry(
        frameTimeNanos = frame.timestamp,
        timeBasis = TimeBasis.CAMERA_REALTIME,
        intrinsics = intrinsics,
        gravityInCamera = down,
        gravityAgeNanos = 3_000_000,
        phone = PhoneGeometrySettings.DEFAULT,
        quality = GeometryQuality.Ok,
    )

    private fun output(seed: Int) = FloatArray(9 * 16) { i -> 1f + ((i * 7 + seed) % 13) / 10f }

    private fun run(frame: ImageFrame, values: FloatArray, fitter: GroundFitter, ordinal: Long): DepthEvidenceRun {
        val map = DisparityMap(9, 16, values, transform)
        val observation = fitter.fit(map, intrinsics, down, 9, 16, frame.timestamp)
        val depthRun = DepthRun(map, 0, 0.5f, 1, 2, 3, MlRuntimeInfo("GPU", "explicit", "native_yuv", "kotlin_ground_fit"), "1x3x16x9>1x16x9")
        return DepthEvidenceRun(DepthRunTrace.PUBLISHED, ordinal, geometry(frame), 9, 16, depthRun, observation)
    }

    private fun read(sessionId: String) = CaptureSessionReader.read(File(tmp.root, sessionId)).orThrow()

    @Test
    fun `a run's record holds its planes as copied, its output and its fit, and the fit replays under the rule the manifest names`() = runBlocking {
        val evidence = stream()
        val capture = controller(evidence)
        capture.onSessionStarted("s1", null)!!.join()
        val fitter = GroundFitter()
        val frames = (1L..3L).map { yuvFrame(it) }
        val planesAtCopy = frames.map { it.yuvData!!.y.bytes.copyOf() }
        for ((i, frame) in frames.withIndex()) {
            val slot = evidence.copy("s1", 1, i + 1L, frame)!!
            // A run without an output gives its copy back, and writes nothing.
            evidence.copy("s1", 1, 100L + i, frame)!!.discard()
            // The lane gives the frame back; the source reuses its arrays.
            frame.yuvData!!.y.bytes.fill(0)
            slot.submit(run(frame, output(i), fitter, i.toLong()))
        }
        capture.onSessionFinished().join()

        val session = read("s1")
        val stats = session.manifest.depthEvidence!!.stats
        assertEquals(3L, stats.recordsWritten)
        assertEquals(0L, stats.dropped)
        assertEquals(3, session.depthEvidence.size)
        for ((i, record) in session.depthEvidence.withIndex()) {
            assertEquals(i + 1L, record.runId)
            assertEquals(i.toLong(), record.fitOrdinal)
            assertEquals(frames[i].sequenceNumber, record.frameSequence)
            assertEquals(90, record.frame.rotationDegrees)
            assertEquals(12, record.frame.y.rowStride)
            assertEquals(2, record.frame.u.pixelStride)
            assertEquals(4, record.frame.u.width)
            assertArrayEquals("the bytes at the copy, not after", planesAtCopy[i], CaptureSessionReader.depthBytes(session, record.frame.y.data))
            assertArrayEquals(frames[i].yuvData!!.u.bytes, CaptureSessionReader.depthBytes(session, record.frame.u.data))
            assertArrayEquals(output(i), CaptureSessionReader.depthOutput(session, record), 0f)
            assertEquals(3_000_000L, record.geometry.gravityAgeNanos)
            assertEquals("PAD_KEEP_ASPECT", record.transform.policy)
        }
        // The guard rule the lane's fitter takes by default, so the replay fits as the phone did.
        assertEquals(fitter.guardRule.id, session.manifest.depthEvidence!!.groundFitRule)
        assertEquals(SupportGuardRule.CURRENT.id, session.manifest.depthEvidence!!.groundFitRule)
        val report = DepthEvidenceReplay().replay(
            session.depthEvidence,
            { CaptureSessionReader.depthOutput(session, it) },
            recordedRule = session.manifest.depthEvidence!!.groundFitRule,
        ).single()
        assertEquals(3, report.exactRuns)
        assertEquals(0, report.mismatchCount)
    }

    @Test
    fun `a damaged blob is found by its CRC, never read as data`() = runBlocking {
        val evidence = stream()
        val capture = controller(evidence)
        capture.onSessionStarted("s1", null)!!.join()
        val frame = yuvFrame(1)
        evidence.copy("s1", 1, 1, frame)!!.submit(run(frame, output(0), GroundFitter(), 0))
        capture.onSessionFinished().join()
        val data = File(File(tmp.root, "s1"), DepthEvidenceFiles.DATA_FILE)
        val bytes = data.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        data.writeBytes(bytes)
        val session = read("s1")
        val failure = runCatching { CaptureSessionReader.depthOutput(session, session.depthEvidence.single()) }.exceptionOrNull()
        assertTrue("$failure", failure is java.io.IOException && "CRC32" in failure.message!!)
    }

    @Test
    fun `a run that finds no free buffer is dropped and counted`() = runBlocking {
        val evidence = stream(poolSize = 1)
        val capture = controller(evidence)
        capture.onSessionStarted("s1", null)!!.join()
        val first = evidence.copy("s1", 1, 1, yuvFrame(1))
        assertNull("the only buffer is held", evidence.copy("s1", 1, 2, yuvFrame(2)))
        first!!.submit(run(yuvFrame(1), output(0), GroundFitter(), 0))
        capture.onSessionFinished().join()
        val stats = read("s1").manifest.depthEvidence!!.stats
        assertEquals(1L, stats.recordsWritten)
        assertEquals(1L, stats.droppedNoBuffer)
        assertEquals(1L, stats.dropped)
        assertEquals(2L, stats.firstDroppedRunId)
        assertEquals(1L, stats.firstDroppedGeneration)
    }

    @Test
    fun `a full queue drops the run rather than wait, and the count says so`() = runBlocking {
        val evidence = stream(queueCapacity = 1)
        // No directory yet: what is submitted waits in the queue.
        evidence.startSession("s1")
        val fitter = GroundFitter()
        evidence.copy("s1", 1, 1, yuvFrame(1))!!.submit(run(yuvFrame(1), output(0), fitter, 0))
        evidence.copy("s1", 1, 2, yuvFrame(2))!!.submit(run(yuvFrame(2), output(1), fitter, 1))
        val directory = tmp.newFolder("s1")
        evidence.attach("s1", directory)
        val stats = evidence.finish("s1")!!
        assertEquals(1L, stats.recordsWritten)
        assertEquals(1L, stats.droppedQueueFull)
        assertEquals(2L, stats.firstDroppedRunId)
        assertEquals(1, File(directory, DepthEvidenceFiles.RECORDS_FILE).readLines().count { it.isNotBlank() })
    }

    @Test
    fun `a run that ends after its capture is not written, nor into the next capture`() = runBlocking {
        val evidence = stream()
        val capture = controller(evidence)
        capture.onSessionStarted("s1", null)!!.join()
        val late = evidence.copy("s1", 1, 1, yuvFrame(1))!!
        capture.onSessionFinished().join()
        capture.onSessionStarted("s2", null)!!.join()
        late.submit(run(yuvFrame(1), output(0), GroundFitter(), 0))
        capture.onSessionFinished().join()
        assertTrue(read("s1").depthEvidence.isEmpty())
        assertTrue(read("s2").depthEvidence.isEmpty())
        assertNull("a copy for an ended capture", evidence.copy("s1", 1, 2, yuvFrame(2)))
        // The capture that lent the copy counted it as dropped in the manifest it wrote, before the run came back.
        val stats = read("s1").manifest.depthEvidence!!.stats
        assertEquals(1L, stats.dropped)
        assertEquals(1L, stats.droppedAtEnd)
        assertEquals(1L, stats.firstDroppedRunId)
        assertEquals(0L, read("s2").manifest.depthEvidence!!.stats.dropped)
    }

    @Test
    fun `a copy under way when the stream finishes is counted by the finish, not after it`() = runBlocking {
        val registered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val evidence = DepthEvidenceCapture(
            log,
            writer.asCoroutineDispatcher(),
            afterRegister = {
                registered.countDown()
                finished.await(5, TimeUnit.SECONDS)
            },
        )
        evidence.startSession("s1")
        val directory = tmp.newFolder("s1")
        evidence.attach("s1", directory)
        val slot = AtomicReference<DepthEvidenceSlot?>()
        val copying = thread { slot.set(evidence.copy("s1", 1, 7, yuvFrame(7))) }
        assertTrue("the copy is registered", registered.await(5, TimeUnit.SECONDS))
        // The stream finishes while the copy is being made.
        val stats = evidence.finish("s1")!!
        finished.countDown()
        copying.join(5_000)
        assertEquals(1L, stats.droppedAtEnd)
        assertEquals(1L, stats.dropped)
        assertEquals(7L, stats.firstDroppedRunId)
        // The copy completes and the lane submits it: nothing more is written or counted.
        slot.get()?.submit(run(yuvFrame(7), output(0), GroundFitter(), 0))
        assertFalse(File(directory, DepthEvidenceFiles.RECORDS_FILE).readLines().any { it.isNotBlank() })
    }

    @Test
    fun `a copy still lent out when the stream finishes is counted then, and settles nothing after`() = runBlocking {
        val evidence = stream()
        evidence.startSession("s1")
        evidence.attach("s1", tmp.newFolder("s1"))
        val fitter = GroundFitter()
        val submitted = evidence.copy("s1", 1, 1, yuvFrame(1))!!
        submitted.submit(run(yuvFrame(1), output(0), fitter, 0))
        val late = evidence.copy("s1", 1, 2, yuvFrame(2))!!
        val discarded = evidence.copy("s1", 1, 3, yuvFrame(3))!!
        val stats = evidence.finish("s1")!!
        assertEquals(1L, stats.recordsWritten)
        // Both copies still out are dropped at the end, whatever the lane does with them next.
        assertEquals(2L, stats.droppedAtEnd)
        assertEquals(2L, stats.dropped)
        assertEquals(2L, stats.firstDroppedRunId)
        late.submit(run(yuvFrame(2), output(1), fitter, 1))
        discarded.discard()
        submitted.discard()
        assertEquals(1, File(tmp.root, "s1/${DepthEvidenceFiles.RECORDS_FILE}").readLines().count { it.isNotBlank() })
    }

    @Test
    fun `the stream is not recorded with the timing burst, nor when not selected`() = runBlocking {
        val burst = stream()
        controller(burst, mode = CaptureModes.TIMING_SYNC).onSessionStarted("burst", null)!!.join()
        assertNull(burst.copy("burst", 1, 1, yuvFrame(1)))
        val off = stream()
        val capture = controller(off, selected = false)
        capture.onSessionStarted("off", null)!!.join()
        assertNull(off.copy("off", 1, 1, yuvFrame(1)))
        capture.onSessionFinished().join()
        assertNull(read("off").manifest.depthEvidence)
        assertFalse(File(File(tmp.root, "off"), DepthEvidenceFiles.RECORDS_FILE).exists())
    }
}
