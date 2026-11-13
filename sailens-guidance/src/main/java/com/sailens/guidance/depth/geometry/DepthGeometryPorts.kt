package com.sailens.guidance.depth.geometry

import com.sailens.core.frame.ImageFrame
import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DepthMap
import com.sailens.vision.depth.DepthRun
import com.sailens.vision.depth.LiteRtDepthRunner

/**
 * Whatever makes the lane's depth maps, as the lane sees it: [prepare] reads the frame (CPU), [infer] does not. The
 * lane gives the frame back between the two. The source's kind is in its maps (`DepthMap.kind`); the lane and the fit
 * branch on that, never on which producer it is. The depth model (`LiteRtDepthRunner` behind [LiteRtDepthProducer])
 * in the app; a fake in tests and in the simulator.
 */
interface DepthProducer {
    /**
     * Whether [infer] runs on the accelerator, and so waits for the accelerator coordinator's admission and pauses when
     * it asks the background to yield. A model does; a source whose depth comes ready with the frame does not.
     */
    val needsAccelerator: Boolean

    suspend fun initialize()

    /** Null when the frame cannot be taken (the native preprocessing could not take it). */
    suspend fun prepare(frame: ImageFrame): DepthPreparedInput?

    suspend fun infer(input: DepthPreparedInput): DepthRun<DepthMap>

    suspend fun release()
}

/**
 * Thrown by [DepthProducer.initialize] when the source cannot be used as it stands -- `mapping`: what it reports does
 * not place its depth on the camera image as a scale per axis and an offset. The capability is unavailable with
 * [reason]; nothing is approximated.
 */
class DepthSourceUnavailable(val reason: String) : Exception(reason) {
    companion object {
        const val MAPPING: String = "mapping"
    }
}

/** A frame's input, written by [DepthProducer.prepare]; [payload] belongs to the producer. */
class DepthPreparedInput(
    val transform: DepthInputTransform,
    val detail: Float?,
    val preprocessMs: Long,
    val payload: Any? = null,
)

/** The depth model: its inference runs on the accelerator. */
class LiteRtDepthProducer(private val runner: LiteRtDepthRunner) : DepthProducer {
    override val needsAccelerator: Boolean get() = true

    override suspend fun initialize() = runner.initialize()

    override suspend fun prepare(frame: ImageFrame): DepthPreparedInput? =
        runner.prepare(frame)?.let { DepthPreparedInput(it.transform, it.detail, it.preprocessMs, it) }

    override suspend fun infer(input: DepthPreparedInput): DepthRun<DepthMap> =
        runner.infer(input.payload as com.sailens.vision.depth.DepthPrepared)

    override suspend fun release() = runner.release()
}

/** Whether there is a depth model where the host looks for one, and whether its tensors fit the contract. */
fun interface DepthModelCheck {
    fun check(): DepthModelCheckResult

    companion object {
        /** A host with no depth model. */
        val None: DepthModelCheck = DepthModelCheck { DepthModelCheckResult.Missing }
    }
}

sealed interface DepthModelCheckResult {
    data object Compatible : DepthModelCheckResult

    /** No file: depth is not configured. */
    data object Missing : DepthModelCheckResult

    /** A file whose tensors are outside the contract. */
    data class Unreadable(val detail: String) : DepthModelCheckResult
}

/** The device's thermal status, as far as the depth lane cares. */
enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE }

/** Where the thermal status comes from; the shell implements it on `PowerManager`. */
fun interface ThermalStatusSource {
    fun current(): ThermalLevel

    companion object {
        val None: ThermalStatusSource = ThermalStatusSource { ThermalLevel.NONE }
    }
}

/**
 * The depth lane's run choices. Not part of `PerceptionConfig`, which captures record: the lane is not an
 * input to any decision, and turning it on must not change what a capture says it ran.
 *
 * @param maxAgePeriods an observation older than this many periods (by frame time) is stale.
 * @param maxConsecutiveFailures this many failed attempts in a row stop the session's lane.
 * @param gridWidth the ground fit's label grid (portrait 9:16), the size the experiments measured on.
 * @param maxUnknownFraction a metric map with more of its pixels unknown than this measured nothing (`no_measurement`).
 * @param maxViewOffsetPeriods a map whose view is more than this many periods from its frame's time is skipped
 *   (`depth_time_mismatch`): the frame's facts do not place it. A measurement older than its view is fine.
 */
data class DepthGeometryConfig(
    val targetFps: Float = 3f,
    /** At MODERATE thermal status. SEVERE and above pause the lane. */
    val throttledFps: Float = 1f,
    val maxAgePeriods: Int = 2,
    val maxConsecutiveFailures: Int = 30,
    val gridWidth: Int = 90,
    val gridHeight: Int = 160,
    val maxUnknownFraction: Float = 0.8f,
    val maxViewOffsetPeriods: Int = 1,
) {
    val frameIntervalMs: Long get() = (1000f / targetFps).toLong()
}

/**
 * Whether the depth lane runs, from the next Guidance session on (like the ground-contact switch). Off by
 * default; only the debug settings switch it on.
 */
class DepthGeometrySwitch(initial: Boolean = false) {
    @Volatile
    var selected: Boolean = initial
        private set

    fun select(enabled: Boolean) {
        selected = enabled
    }
}
