package com.sailens.shell.consistency

import com.sailens.camera.FrameLease
import com.sailens.camera.FrameSnapshotProvider
import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.shell.capture.RecordingLog
import com.sailens.shell.capture.yuvFrame
import com.sailens.vision.consistency.ClassMapAgreement
import com.sailens.vision.consistency.ConsistencyVerdict
import com.sailens.vision.consistency.DetectionAgreement
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ModelConsistencyCheckerTest {

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
        runFrame = { frame ->
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
    ) = ModelConsistencyChecker(semantic, detection, snapshots, RecordingLog(), frameCount = 3, frameIntervalMs = 0)

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
