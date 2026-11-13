package com.sailens.shell.consistency

import com.sailens.camera.FrameLease
import com.sailens.camera.FrameSnapshotProvider
import com.sailens.core.frame.ImageFrame
import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundFitValidity
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.shell.capture.RecordingLog
import com.sailens.shell.capture.yuvFrame
import com.sailens.vision.consistency.ClassMapAgreement
import com.sailens.vision.consistency.ConsistencyVerdict
import com.sailens.vision.consistency.DepthConsistencyThresholds
import com.sailens.vision.consistency.DepthFrameAgreement
import com.sailens.vision.consistency.DetectionAgreement
import com.sailens.vision.consistency.DisparityAgreement
import com.sailens.vision.consistency.GroundFitAgreement
import com.sailens.vision.consistency.judgeDepth
import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.ResizePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ModelConsistencyCheckerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Hands out fresh frames; after [available] of them the camera times out (null). */
    private class Snapshots(private val available: Int = Int.MAX_VALUE) : FrameSnapshotProvider {
        private val seq = AtomicLong()
        override fun currentFrame(maxAgeMs: Long): ImageFrame? =
            if (seq.get() < available) yuvFrame(seq.incrementAndGet()) else null
        override suspend fun awaitCurrentFrame(maxAgeMs: Long, timeoutMs: Long): ImageFrame? = currentFrame(maxAgeMs)
        override fun openSnapshotLease(): FrameLease = object : FrameLease {
            override fun close() = Unit
        }
    }

    private val agreeing = ClassMapAgreement(1f, 48, emptyList())

    /** A checked pair whose release suspends, as LiteRT's does (it switches dispatcher). */
    private fun <A> pair(answer: A, released: AtomicBoolean, onFrame: (ImageFrame) -> Unit = {}) = ComparisonPair(
        status = ModelCheckStatus.CHECKED,
        modelSource = "model.tflite",
        runFrame = { (frame) ->
            onFrame(frame)
            answer to AcceleratorPair("GPU", "CPU")
        },
        releaseRunners = {
            delay(1)
            released.set(true)
        },
    )

    private fun checker(
        semantic: ComparisonPairFactory<ClassMapAgreement>,
        detection: ComparisonPairFactory<DetectionAgreement>,
        snapshots: FrameSnapshotProvider = Snapshots(),
        depth: ComparisonPairFactory<DepthFrameEvidence> = ComparisonPairFactory { ComparisonPair.notApplicable() },
        geometry: ConsistencyGeometry = ConsistencyGeometry.None,
    ) = ModelConsistencyChecker(
        semantic, detection, snapshots, RecordingLog(), frameCount = 3, frameIntervalMs = 0,
        depthPairs = depth, geometry = geometry,
    )

    /** Records what the check asks of the gravity source, in order, into [events]. */
    private class RecordingGeometry(private val events: MutableList<String>) : ConsistencyGeometry {
        var started = 0
        var stopped = 0
        override fun start() { started++; events += "start" }
        override fun stop() { stopped++; events += "stop" }
        override suspend fun awaitGravity(timeoutMs: Long) { events += "await" }
        override fun resolve(frame: ImageFrame): CameraGeometry? { events += "resolve"; return null }
    }

    private val depthEvidence = DepthFrameEvidence(DepthFrameAgreement(DisparityAgreement(0.004f, 0.01f, 1000), fit = null))

    @Test
    fun `without a depth model the gravity source is never started`() = runBlocking {
        val geometry = RecordingGeometry(mutableListOf())
        val result = checker(
            semantic = { pair(agreeing, AtomicBoolean()) },
            detection = { ComparisonPair.notApplicable() },
            geometry = geometry,
        ).run()
        assertEquals(0, geometry.started)
        assertEquals(ModelCheckStatus.NOT_APPLICABLE, result.depth.status)
        assertEquals(ConsistencyVerdict.PASS, result.overall)
    }

    @Test
    fun `each frame's geometry is resolved before any model runs on it`() = runBlocking {
        val events = mutableListOf<String>()
        val geometry = RecordingGeometry(events)
        checker(
            semantic = { pair(agreeing, AtomicBoolean()) { events += "semantic" } },
            detection = { ComparisonPair.notApplicable() },
            depth = { pair(depthEvidence, AtomicBoolean()) { events += "depth" } },
            geometry = geometry,
        ).run()
        assertEquals(
            listOf("start", "await") + List(3) { listOf("resolve", "semantic", "depth") }.flatten() + "stop",
            events,
        )
    }

    @Test
    fun `the gravity source is released when the run is cancelled`() = runBlocking {
        val geometry = RecordingGeometry(mutableListOf())
        val inDepth = CompletableDeferred<Unit>()
        val run = launch {
            checker(
                semantic = { ComparisonPair.notApplicable() },
                detection = { ComparisonPair.notApplicable() },
                depth = {
                    ComparisonPair(
                        status = ModelCheckStatus.CHECKED,
                        runFrame = { inDepth.complete(Unit); awaitCancellation() },
                    )
                },
                geometry = geometry,
            ).run()
        }
        inDepth.await()
        run.cancelAndJoin()
        assertEquals(1, geometry.started)
        assertEquals(1, geometry.stopped)
    }

    private val depthTransform = DepthInputTransform.create(9, 16, 9, 16, ResizePolicy.PAD_KEEP_ASPECT)
    private fun depthMap(values: FloatArray) = DisparityMap(9, 16, values, depthTransform)

    /** Disparity growing toward the bottom of the frame, as ground does. */
    private val groundValues = FloatArray(9 * 16) { i -> 1f + (i / 9) * 0.25f }
    private val fitGeometry = CameraGeometry(
        frameTimeNanos = 0,
        timeBasis = TimeBasis.CAMERA_REALTIME,
        intrinsics = CameraIntrinsics(10f, 10f, 4.5f, 8f, 9, 16, IntrinsicsSource.CALIBRATION),
        gravityInCamera = Vec3(0f, 0.9f, 0.43f),
        gravityAgeNanos = 0,
        phone = PhoneGeometrySettings.DEFAULT,
        quality = GeometryQuality.Ok,
    )

    @Test
    fun `a NaN in either output is a failed frame, not fitted, whatever the other values say`() {
        val finite = compareDepthFrame(fitGeometry, depthMap(groundValues), depthMap(groundValues.copyOf()), GroundFitter(), GroundFitter())
        assertNotNull("a finite frame with geometry is fitted", finite.agreement.fit)
        assertFalse(finite.agreement.hasNonFiniteOutput)

        val withNaN = groundValues.copyOf().also { it[100] = Float.NaN }
        val candidate = compareDepthFrame(fitGeometry, depthMap(groundValues), depthMap(withNaN), GroundFitter(), GroundFitter()).agreement
        assertEquals(1, candidate.candidateNonFinite)
        assertNull("not fitted", candidate.fit)
        // One bad value moves no median: the statistics alone would have passed it.
        assertEquals(0f, candidate.disparity.medianRelativeError, 0f)
        val reference = compareDepthFrame(fitGeometry, depthMap(withNaN), depthMap(groundValues), GroundFitter(), GroundFitter()).agreement
        assertEquals(1, reference.referenceNonFinite)
        assertNull(reference.fit)

        val agreeing = DepthFrameAgreement(DisparityAgreement(0f, 0f, 144), GroundFitAgreement(true, true, 1f))
        assertEquals(ConsistencyVerdict.FAIL, judgeDepth(List(7) { agreeing } + candidate).verdict)
    }

    @Test
    fun `a failed depth check with infinite errors still writes its report, as valid JSON that says why`() {
        val zeros = depthMap(FloatArray(9 * 16))
        val broken = compareDepthFrame(null, zeros, depthMap(FloatArray(9 * 16) { 0.5f }), GroundFitter(), GroundFitter()).agreement
        val nan = compareDepthFrame(null, zeros, depthMap(FloatArray(9 * 16) { Float.NaN }), GroundFitter(), GroundFitter()).agreement
        val consistency = judgeDepth(List(6) { DepthFrameAgreement(DisparityAgreement(0f, 0f, 144), null) } + broken + nan)
        assertEquals(Float.POSITIVE_INFINITY, consistency.maxMedianRelativeError)
        val run = ConsistencyRunResult(
            framesRequested = 8,
            framesUsed = 8,
            framesChanged = 0,
            semantic = ModelOutcome(ModelCheckStatus.NOT_APPLICABLE, null, null, null),
            detection = ModelOutcome(ModelCheckStatus.NOT_APPLICABLE, null, null, null),
            depth = ModelOutcome(ModelCheckStatus.CHECKED, null, "depth.tflite", DepthModelResult(AcceleratorPair("GPU", "CPU"), consistency)),
        )
        val file = ModelConsistencyReportWriter(tmp.newFolder("reports")).write(run.toReport(wallTimeMs = 1, build = null, semanticLabel = { "c$it" }))
        val depth = Json.parseToJsonElement(file.readText()).jsonObject.getValue("depth").jsonObject
        assertEquals("FAIL", depth.getValue("verdict").jsonPrimitive.content)
        assertEquals(JsonNull, depth.getValue("maxMedianRelativeError"))
        assertEquals(JsonNull, depth.getValue("frameMedianRelativeError").jsonArray[6])
        assertEquals(1, depth.getValue("nonFiniteOutputFrames").jsonPrimitive.content.toInt())
        val named = depth.getValue("nonFiniteMetrics").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(named.toString(), "maxMedianRelativeError=Infinity" in named && "frameMedianRelativeError[6]=Infinity" in named)
    }

    /** Eight frames through the real comparison, with one fitter per side across them as the check has. */
    private fun depthRun(candidateOf: (Int) -> FloatArray): ConsistencyRunResult {
        val referenceFitter = GroundFitter()
        val candidateFitter = GroundFitter()
        val evidence = List(8) { i ->
            compareDepthFrame(fitGeometry, depthMap(groundValues.copyOf()), depthMap(candidateOf(i)), referenceFitter, candidateFitter)
        }
        val consistency = judgeDepth(evidence.map { it.agreement }, DepthConsistencyThresholds(framesRequired = 8))
        return ConsistencyRunResult(
            framesRequested = 8,
            framesUsed = 8,
            framesChanged = 0,
            semantic = ModelOutcome(ModelCheckStatus.NOT_APPLICABLE, null, null, null),
            detection = ModelOutcome(ModelCheckStatus.NOT_APPLICABLE, null, null, null),
            depth = ModelOutcome(
                ModelCheckStatus.CHECKED, null, "depth.tflite",
                DepthModelResult(AcceleratorPair("GPU", "CPU"), consistency, evidence.map { it.detail }),
            ),
        )
    }

    private fun writeDepthReport(run: ConsistencyRunResult, dir: File) =
        ModelConsistencyReportWriter(dir).write(run.toReport(wallTimeMs = 1, build = null, semanticLabel = { "c$it" }), run.depth.result!!.details)

    private fun depthJson(file: File) = Json.parseToJsonElement(file.readText()).jsonObject.getValue("depth").jsonObject

    private fun readFloats(file: File): FloatArray {
        val floats = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }

    @Test
    fun `a depth check whose runs agree keeps the per-frame detail but no outputs`() {
        val run = depthRun { groundValues.copyOf() }
        val dir = tmp.newFolder("agree")
        val depth = depthJson(writeDepthReport(run, dir))
        assertEquals(JsonNull, depth.getValue("depthOutputDirectory"))
        val frames = depth.getValue("depthFrames").jsonArray
        assertEquals(8, frames.size)
        val first = frames[0].jsonObject
        assertEquals("false", first.getValue("disagrees").jsonPrimitive.content)
        assertEquals(DEPTH_REGION_GRID * DEPTH_REGION_GRID, first.getValue("regionMedianRelativeError").jsonArray.size)
        assertEquals(
            first.getValue("referenceFit").jsonObject.getValue("validity"),
            first.getValue("candidateFit").jsonObject.getValue("validity"),
        )
        val geometry = first.getValue("geometry").jsonObject
        assertEquals("10.0", geometry.getValue("fx").jsonPrimitive.content)
        assertEquals("0.9", geometry.getValue("gravityY").jsonPrimitive.content)
        assertEquals(JsonNull, first.getValue("output").jsonObject.getValue("referenceFile"))
        assertEquals(listOf("consistency_1.json"), dir.list()!!.toList())
    }

    @Test
    fun `when a frame disagrees, both outputs of every frame are kept beside the report, as the floats they were`() {
        // Frame 5's candidate has a NaN: a failed run, and so a disagreement.
        val run = depthRun { i -> groundValues.copyOf().also { if (i == 5) it[100] = Float.NaN } }
        val dir = tmp.newFolder("disagree")
        val depth = depthJson(writeDepthReport(run, dir))
        assertEquals("consistency_1_depth", depth.getValue("depthOutputDirectory").jsonPrimitive.content)
        val frames = depth.getValue("depthFrames").jsonArray
        assertEquals("true", frames[5].jsonObject.getValue("disagrees").jsonPrimitive.content)
        assertEquals("false", frames[0].jsonObject.getValue("disagrees").jsonPrimitive.content)
        val details = run.depth.result!!.details
        for (i in 0 until 8) {
            val output = frames[i].jsonObject.getValue("output").jsonObject
            assertEquals("PAD_KEEP_ASPECT", output.getValue("policy").jsonPrimitive.content)
            val reference = readFloats(File(dir, output.getValue("referenceFile").jsonPrimitive.content))
            val candidate = readFloats(File(dir, output.getValue("candidateFile").jsonPrimitive.content))
            assertTrue("frame $i reference", reference.map { it.toRawBits() } == details[i]!!.reference.values.map { it.toRawBits() })
            assertTrue("frame $i candidate", candidate.map { it.toRawBits() } == details[i]!!.candidate.values.map { it.toRawBits() })
        }
        assertTrue(readFloats(File(dir, "consistency_1_depth/frame5_candidate.f32"))[100].isNaN())
    }

    @Test
    fun `outputs that cannot be written are dropped, and the report says why instead of naming them`() {
        val run = depthRun { i -> groundValues.copyOf().also { if (i == 5) it[100] = Float.NaN } }
        val dir = tmp.newFolder("blocked")
        // A file where the output directory would go: the outputs cannot be written under it.
        File(dir, "consistency_1_depth").writeText("in the way")
        val depth = depthJson(writeDepthReport(run, dir))
        assertEquals(JsonNull, depth.getValue("depthOutputDirectory"))
        assertTrue(depth.getValue("depthOutputsError") !is JsonNull)
        depth.getValue("depthFrames").jsonArray.forEach { frame ->
            assertEquals(JsonNull, frame.jsonObject.getValue("output").jsonObject.getValue("referenceFile"))
            assertEquals(JsonNull, frame.jsonObject.getValue("output").jsonObject.getValue("candidateFile"))
        }
    }

    @Test
    fun `label changes are counted by the reference's label and the candidate's`() {
        fun mask(cells: Set<Pair<Int, Int>>) = BinaryMask(FIT_GRID_WIDTH, FIT_GRID_HEIGHT).also { m -> cells.forEach { (x, y) -> m.set(x, y, true) } }
        fun fit(ground: Set<Pair<Int, Int>>, above: Set<Pair<Int, Int>>) = GroundObservation(
            frameTimeNanos = null,
            validity = GroundFitValidity.VALID,
            groundMask = mask(ground),
            aboveGroundMask = mask(above),
            belowGroundMask = mask(emptySet()),
            disparityScale = 1f,
            disparityShift = 0f,
            inlierRatio = 1f,
            planeAgreesWithGravity = true,
            confidence = 1f,
        )
        val reference = fit(ground = setOf(0 to 0, 1 to 0, 2 to 0, 3 to 0), above = setOf(0 to 1))
        val candidate = fit(ground = setOf(0 to 0), above = setOf(1 to 0, 2 to 0, 0 to 1))
        assertEquals(mapOf("ground->above" to 2, "ground->none" to 1), labelChanges(reference, candidate))
        assertEquals(listOf("ground->above", "ground->none"), labelChanges(reference, candidate).keys.toList())
        assertEquals(emptyMap<String, Int>(), labelChanges(reference, reference))
    }

    @Test
    fun `a checked depth model counts towards the overall verdict`() = runBlocking {
        val result = checker(
            semantic = { pair(agreeing, AtomicBoolean()) },
            detection = { ComparisonPair.notApplicable() },
            // Three frames with no geometry: the fit cannot be judged, so the depth model is inconclusive.
            depth = { pair(depthEvidence, AtomicBoolean()) },
            geometry = RecordingGeometry(mutableListOf()),
        ).run()
        assertEquals(ModelCheckStatus.CHECKED, result.depth.status)
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, result.depthVerdict)
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, result.overall)
    }

    @Test
    fun `cancelling while the second pair initialises releases the first`() = runBlocking {
        val semanticReleased = AtomicBoolean()
        val acquiring = CompletableDeferred<Unit>()
        val run = launch {
            checker(
                semantic = { pair(agreeing, semanticReleased) },
                detection = {
                    acquiring.complete(Unit)
                    awaitCancellation()
                },
            ).run()
        }
        acquiring.await()
        run.cancelAndJoin()
        assertTrue("semantic runners leaked", semanticReleased.get())
    }

    @Test
    fun `a failure while the second pair initialises releases the first`() = runBlocking {
        val semanticReleased = AtomicBoolean()
        val failed = runCatching {
            checker(
                semantic = { pair(agreeing, semanticReleased) },
                detection = { error("boom") },
            ).run()
        }
        assertTrue(failed.isFailure)
        assertTrue("semantic runners leaked", semanticReleased.get())
    }

    @Test
    fun `both pairs are released after a run`() = runBlocking {
        val semanticReleased = AtomicBoolean()
        val detectionReleased = AtomicBoolean()
        val result = checker(
            semantic = { pair(agreeing, semanticReleased) },
            detection = { pair(DetectionAgreement(1, 1, 1, 0), detectionReleased) },
        ).run()
        assertTrue(semanticReleased.get() && detectionReleased.get())
        assertEquals(3, result.framesUsed)
        assertEquals(ConsistencyVerdict.PASS, result.overall)
        assertEquals(2, result.modelsChecked)
        // No depth model configured: nothing to check, and the verdict is what it was without one.
        assertEquals(ModelCheckStatus.NOT_APPLICABLE, result.depth.status)
        assertEquals(2, result.modelsApplicable)
    }

    @Test
    fun `a frame whose pixels change between the runs is left out`() = runBlocking {
        val result = checker(
            semantic = { pair(agreeing, AtomicBoolean()) { frame -> frame.yuvData!!.y.bytes[0]++ } },
            detection = { ComparisonPair.notApplicable() },
        ).run()
        assertEquals(0, result.framesUsed)
        assertEquals(3, result.framesChanged)
        assertEquals(ModelCheckStatus.NO_FRAMES, result.semantic.status)
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, result.overall)
    }

    @Test
    fun `one agreeing frame out of three is not enough to pass`() = runBlocking {
        // Three requested: the first two change under the comparison, the third agrees.
        var calls = 0
        val result = checker(
            semantic = { pair(agreeing, AtomicBoolean()) { frame -> if (++calls <= 2) frame.yuvData!!.y.bytes[0]++ } },
            detection = { ComparisonPair.notApplicable() },
        ).run()
        assertEquals(1, result.framesUsed)
        assertEquals(2, result.framesChanged)
        assertEquals(ModelCheckStatus.INCOMPLETE, result.semantic.status)
        assertEquals(ConsistencyVerdict.PASS, result.semantic.result!!.consistency.verdict) // the one frame agreed...
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, result.semanticVerdict) // ...which is not evidence
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, result.overall)
        assertEquals(0, result.modelsChecked)
    }

    @Test
    fun `a camera timeout after one frame is not a pass`() = runBlocking {
        val result = checker(
            semantic = { pair(agreeing, AtomicBoolean()) },
            detection = { pair(DetectionAgreement(1, 1, 1, 0), AtomicBoolean()) },
            snapshots = Snapshots(available = 1),
        ).run()
        assertEquals(1, result.framesUsed)
        assertEquals(ModelCheckStatus.INCOMPLETE, result.detection.status)
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, result.overall)
    }

    @Test
    fun `a failure seen on partial evidence still fails`() = runBlocking {
        val result = checker(
            semantic = { pair(ClassMapAgreement(0.3f, 48, emptyList()), AtomicBoolean()) },
            detection = { ComparisonPair.notApplicable() },
            snapshots = Snapshots(available = 2),
        ).run()
        assertEquals(ModelCheckStatus.INCOMPLETE, result.semantic.status)
        assertEquals(ConsistencyVerdict.FAIL, result.semanticVerdict)
        assertEquals(ConsistencyVerdict.FAIL, result.overall)
    }

    @Test
    fun `the diagnostic decodes far more detections than Guidance keeps`() {
        // With every class allowed, Guidance's cap would let the top ten hide the eleventh.
        assertTrue(DIAGNOSTIC_MAX_DETECTIONS > PerceptionConfig().maxObstacles * 5)
    }

    @Test
    fun `pass needs every applicable model compared and passed`() {
        val checkedPass = ModelCheckStatus.CHECKED to ConsistencyVerdict.PASS
        val checkedFail = ModelCheckStatus.CHECKED to ConsistencyVerdict.FAIL
        val unavailable = ModelCheckStatus.UNAVAILABLE to null
        val notApplicable = ModelCheckStatus.NOT_APPLICABLE to null

        assertEquals(ConsistencyVerdict.PASS, overallVerdict(listOf(checkedPass, checkedPass)))
        assertEquals(ConsistencyVerdict.PASS, overallVerdict(listOf(checkedPass, notApplicable)))
        // One model passed, the other could not be checked: not a pass.
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, overallVerdict(listOf(checkedPass, unavailable)))
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, overallVerdict(listOf(checkedPass, ModelCheckStatus.NO_FRAMES to null)))
        assertEquals(
            ConsistencyVerdict.INCONCLUSIVE,
            overallVerdict(listOf(checkedPass, ModelCheckStatus.CHECKED to ConsistencyVerdict.INCONCLUSIVE)),
        )
        // A known failure is a failure whatever else happened.
        assertEquals(ConsistencyVerdict.FAIL, overallVerdict(listOf(checkedFail, unavailable)))
        assertEquals(
            ConsistencyVerdict.INCONCLUSIVE,
            overallVerdict(listOf(checkedPass, ModelCheckStatus.INCOMPLETE to ConsistencyVerdict.INCONCLUSIVE)),
        )
        assertNull(overallVerdict(listOf(notApplicable, notApplicable)))
    }
}
