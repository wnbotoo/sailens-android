package com.sailens.guidance.trace.regression

import com.sailens.core.frame.ImageFrame
import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.model.analysis.ConnectivityStats
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.analysis.FrameQualityResult
import com.sailens.guidance.trace.capture.ConnectivityRecord
import com.sailens.guidance.trace.capture.DetectionRunRecord
import com.sailens.guidance.trace.capture.FrameQualityRecord
import com.sailens.guidance.trace.capture.GeometryRecord
import com.sailens.guidance.trace.capture.RegressionFrameRecord
import com.sailens.guidance.trace.capture.SemanticRunRecord
import com.sailens.guidance.trace.capture.SemanticRuns

/**
 * Turns the [RegressionRecorder] hooks of one frame into its [RegressionFrameRecord] (M0b). Shared by
 * the capture (debug shell) and the round-trip tests, so what is tested is what is recorded.
 *
 * Thread-safe: the model hooks arrive from the model dispatchers while the pipeline awaits them.
 * The class map is copied while its hook runs, into an array from [borrowClassMap]; null there (no
 * array free) costs the frame.
 */
class RegressionFrameAssembler(
    private val borrowClassMap: (size: Int) -> IntArray?,
) {
    /** What [endFrame] produced. */
    sealed interface Outcome {
        val seq: Long

        /** The frame's record and its class map copy, when the model ran. */
        class Frame(val record: RegressionFrameRecord, val classIds: IntArray?, val width: Int, val height: Int) : Outcome {
            override val seq: Long get() = record.seq
        }

        /** The frame cannot be recorded completely; [classIds] is a borrowed copy to give back. */
        class Lost(override val seq: Long, val why: String, val classIds: IntArray?) : Outcome
    }

    private class Builder(val frame: ImageFrame) {
        val clocks = LinkedHashMap<String, MutableList<Long>>()
        var semantic: SemanticRunRecord? = null
        var classIds: IntArray? = null
        var classWidth = 0
        var classHeight = 0
        var detection: DetectionRunRecord? = null
        val connectivity = mutableListOf<ConnectivityRecord>()
        var quality: FrameQualityRecord? = null
        var stationary: Boolean? = null
        var geometry: GeometryRecord? = null
        var incomplete: String? = null
    }

    private val lock = Any()
    private var current: Builder? = null
    private var crcScratch = LongArray(0)

    /** Starts [frame]; returns the previous frame as lost if it never ended. */
    fun beginFrame(frame: ImageFrame): Outcome.Lost? = synchronized(lock) {
        val unfinished = current?.let { Outcome.Lost(it.frame.sequenceNumber, "frame ${it.frame.sequenceNumber} never ended", it.classIds) }
        current = Builder(frame)
        unfinished
    }

    /** @return false when no frame is open (a clock read outside a frame). */
    fun onClock(component: String, value: Long): Boolean = synchronized(lock) {
        val builder = current ?: return false
        builder.clocks.getOrPut(component) { mutableListOf() } += value
        true
    }

    fun onSemantic(frame: ImageFrame, ran: Boolean, output: SegmentationOutput?, failure: Throwable?) = synchronized(lock) {
        val builder = current?.takeIf { it.frame === frame } ?: return@synchronized
        builder.semantic = when {
            failure != null -> SemanticRunRecord(SemanticRuns.FAILED, failure = failure.toString())
            !ran -> SemanticRunRecord(SemanticRuns.CACHED)
            output == null -> SemanticRunRecord(SemanticRuns.FAILED, failure = "no output")
            else -> {
                val mask = output.mask
                val size = mask.width * mask.height
                val copy = borrowClassMap(size)
                if (copy == null) {
                    builder.incomplete = "no class map copy free"
                } else {
                    // Owned by the frame before copying, so a failed copy is still given back.
                    builder.classIds = copy
                    System.arraycopy(mask.classMap, 0, copy, 0, size)
                    builder.classWidth = mask.width
                    builder.classHeight = mask.height
                }
                SemanticRunRecord(SemanticRuns.RAN, stats = output.analysisStats?.toRecord())
            }
        }
    }

    fun onDetection(frame: ImageFrame, detections: List<ObstacleDetection>?, failure: Throwable?) = synchronized(lock) {
        val builder = current?.takeIf { it.frame === frame } ?: return@synchronized
        builder.detection = DetectionRunRecord(detections = detections?.map { it.toItem() }.orEmpty(), failure = failure?.toString())
    }

    fun onConnectivity(passableMask: BinaryMask, stats: ConnectivityStats?) = synchronized(lock) {
        val builder = current ?: return@synchronized
        val words = (passableMask.width * passableMask.height + Long.SIZE_BITS - 1) / Long.SIZE_BITS
        if (crcScratch.size < words) crcScratch = LongArray(words)
        builder.connectivity += ConnectivityRecord(
            inputCrc32 = passableMask.regressionCrc32(crcScratch),
            width = passableMask.width,
            height = passableMask.height,
            stats = stats?.toRecord(),
        )
    }

    fun onFrameQuality(result: FrameQualityResult) = synchronized(lock) {
        current?.quality = FrameQualityRecord(
            meanLuma = result.measurement?.meanLuma,
            stdLuma = result.measurement?.stdLuma,
            sharpness = result.measurement?.sharpness,
            classified = result.classified.name,
        )
    }

    fun onGeometry(geometry: CameraGeometry) = synchronized(lock) {
        current?.geometry = geometry.toRecord()
    }

    fun onStationary(stationary: Boolean) = synchronized(lock) {
        current?.stationary = stationary
    }

    /** A hook failed while recording: the open frame cannot be complete. @return false when none is open. */
    fun markIncomplete(why: String): Boolean = synchronized(lock) {
        val builder = current ?: return false
        builder.incomplete = why
        true
    }

    fun endFrame(frame: ImageFrame, events: List<SceneEvent>, failure: Throwable?): Outcome {
        val builder = synchronized(lock) { current?.takeIf { it.frame === frame }.also { current = null } }
            ?: return Outcome.Lost(frame.sequenceNumber, "frame ended without beginning", null)
        builder.incomplete?.let { return Outcome.Lost(frame.sequenceNumber, it, builder.classIds) }
        val record = RegressionFrameRecord(
            seq = frame.sequenceNumber,
            sensorTimestampNanos = frame.timestamp,
            width = frame.width,
            height = frame.height,
            rotationDegrees = frame.rotationDegrees,
            clocks = builder.clocks,
            semantic = builder.semantic,
            detection = builder.detection,
            connectivity = builder.connectivity,
            quality = builder.quality,
            stationary = builder.stationary,
            geometry = builder.geometry,
            events = events.map { it.toRecord() },
            failure = failure?.toString(),
        )
        return Outcome.Frame(record, builder.classIds, builder.classWidth, builder.classHeight)
    }
}
