package com.sailens.guidance.sim

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.processor.analysis.FrameQualityAnalyzer
import com.sailens.guidance.model.scene.SceneResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The simulator's own controls: model initialisation held, failed or uncancellable, and the camera
 * frames the perturbation fields make. The defaults are what every golden runs with; these tests show
 * that the defaults initialise nothing and that each control does what it says.
 */
class SimulatedPipelineTest {
    private val scenario = Scenario(
        name = "simulated_pipeline_probe",
        description = "Standing on an empty sidewalk.",
        frames = List(6) { i ->
            ScenarioFrame(
                Pose(Scenarios.HEIGHT, Scenarios.PITCH), World(Scenarios.street()), stationary = true,
                lighting = when (i) {
                    1 -> Lighting.COVERED
                    2 -> Lighting.DARK
                    else -> Lighting.NORMAL
                },
                delivery = if (i == 4) FrameDelivery.Frozen(2) else FrameDelivery.Delivered,
            )
        },
    )

    private fun SimulatedPipeline.firstFrame(onSubscribed: () -> Unit = {}): Flow<ImageFrame> = flow {
        onSubscribed()
        clock.advanceBy(scenario.stepMs)
        emit(cameraFrame(0))
    }

    private fun SimulatedPipeline.collect(frames: Flow<ImageFrame>): List<SceneResult> = runBlocking {
        start(frames, releaseFrame = {}, semanticMaskSnapshot = { false }).toList()
    }

    @Test
    fun `by default both models are ready and the pipeline never initialises them`() {
        val pipeline = SimulatedPipeline(scenario)
        assertTrue(pipeline.segmentation.isInitialized)
        assertTrue(pipeline.detector.isInitialized)
        assertEquals(1, pipeline.collect(pipeline.firstFrame()).size)
        assertEquals(0, pipeline.segmentation.initializations.get())
        assertEquals(0, pipeline.detector.initializations.get())
    }

    @Test
    fun `a held segmentation initialisation keeps the frames unread until it is released`() = held(segmentation = true)

    @Test
    fun `a held detector initialisation keeps the frames unread until it is released`() = held(segmentation = false)

    private fun held(segmentation: Boolean) = runBlocking {
        val gate = ModelInitGate()
        val pipeline = if (segmentation) SimulatedPipeline(scenario, semanticInit = gate) else SimulatedPipeline(scenario, detectionInit = gate)
        val initialized = { if (segmentation) pipeline.segmentation.isInitialized else pipeline.detector.isInitialized }
        val subscribed = CompletableDeferred<Unit>()
        val results = async(Dispatchers.Default) {
            pipeline.start(pipeline.firstFrame { subscribed.complete(Unit) }, releaseFrame = {}, semanticMaskSnapshot = { false }).toList()
        }
        awaitTrue { gate.waits.get() == 1 }
        assertFalse(initialized())
        delay(100)
        assertFalse("no frame is read while the model initialises", subscribed.isCompleted)
        gate.release()
        assertEquals(1, withTimeout(TIMEOUT_MS) { results.await() }.size)
        assertTrue(initialized())
        assertEquals(1, if (segmentation) pipeline.segmentation.initializations.get() else pipeline.detector.initializations.get())
    }

    @Test
    fun `a failed initialisation ends the pipeline with its cause`() {
        val gate = ModelInitGate()
        val pipeline = SimulatedPipeline(scenario, semanticInit = gate)
        gate.fail(IllegalStateException("the model did not load"))
        try {
            pipeline.collect(pipeline.firstFrame { fail("no frame is read") })
            fail("the pipeline must throw")
        } catch (error: IllegalStateException) {
            assertEquals("the model did not load", error.message)
        }
        assertFalse(pipeline.segmentation.isInitialized)
    }

    @Test
    fun `a cancellable initialisation gives way to cancellation, an uncancellable one only to its release`() = runBlocking {
        for (cancellable in listOf(true, false)) {
            val gate = ModelInitGate(cancellable = cancellable)
            val pipeline = SimulatedPipeline(scenario, semanticInit = gate)
            val job = launch(Dispatchers.Default) {
                pipeline.start(pipeline.firstFrame(), releaseFrame = {}, semanticMaskSnapshot = { false }).toList()
            }
            awaitTrue { gate.waits.get() == 1 }
            job.cancel()
            if (cancellable) {
                withTimeout(TIMEOUT_MS) { job.join() }
            } else {
                delay(200)
                assertFalse("still initialising after the cancellation", job.isCompleted)
                gate.release()
                withTimeout(TIMEOUT_MS) { job.join() }
                assertTrue("released, it initialised", pipeline.segmentation.isInitialized)
            }
            assertTrue(job.isCancelled)
        }
    }

    @Test
    fun `stopping releases both models once`() = runBlocking {
        val pipeline = SimulatedPipeline(scenario)
        pipeline.stop()
        pipeline.stop.release()
        assertEquals(1, pipeline.segmentation.releases.get())
        assertEquals(1, pipeline.detector.releases.get())
    }

    @Test
    fun `the camera frame is textured, flat when covered, dark but textured in the dark`() {
        val pipeline = SimulatedPipeline(scenario)
        val quality = FrameQualityAnalyzer()
        assertEquals(FrameQuality.OK, quality.classify(pipeline.cameraFrame(0)))
        assertEquals(FrameQuality.OBSTRUCTED, quality.classify(pipeline.cameraFrame(1)))
        val dark = pipeline.cameraFrame(2)
        assertEquals(FrameQuality.TOO_DARK, quality.classify(dark))
        val measured = checkNotNull(quality.measure(dark))
        assertEquals(20f, measured.meanLuma, 1.5f)
        assertEquals(10f, measured.stdLuma, 1.5f)
    }

    @Test
    fun `a frozen frame has its own timestamps and the picture of the frame it is frozen on`() {
        val pipeline = SimulatedPipeline(scenario)
        val frozen = pipeline.cameraFrame(4)
        val source = pipeline.cameraFrame(2)
        assertEquals(5L, frozen.sequenceNumber)
        assertEquals(scenario.facts(4).frame.sensorTimestampNanos, frozen.timestamp)
        assertArrayEquals(checkNotNull(source.yuvData).y.bytes, checkNotNull(frozen.yuvData).y.bytes)
    }

    @Test
    fun `an unaligned scenario keeps the timestamps it always had`() {
        for (index in scenario.frames.indices) {
            val frame = scenario.facts(index).frame
            assertEquals((index + 1L) * scenario.stepMs * 1_000_000, frame.sensorTimestampNanos)
            assertEquals(frame.sensorTimestampNanos, frame.receivedElapsedRealtimeNanos)
        }
    }

    private suspend fun awaitTrue(condition: () -> Boolean) = withTimeout(TIMEOUT_MS) {
        while (!condition()) delay(5)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
