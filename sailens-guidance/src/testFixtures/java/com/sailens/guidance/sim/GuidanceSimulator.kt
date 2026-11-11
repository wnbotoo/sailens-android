package com.sailens.guidance.sim

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.scene.SceneResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Runs a [Scenario] through the real Guidance pipeline (`StartSceneAnalysisUseCase` and everything
 * below it, wired by [SimulatedPipeline]) with fakes only at the model boundary (docs/geometry.md §7).
 * Deterministic: a fake clock that advances by the scenario's step before each frame, lost frames
 * included, seeded camera noise, and one frame in the pipeline at a time.
 *
 * With the ground-contact distance off, Guidance decides on no pose; the scenario's pose shapes what
 * the models report (the class map and boxes move with pitch and height). The camera geometry is fed
 * the scenario's own raw facts
 * ([Scenario.facts]) -- camera record, sensor-to-buffer transform, gravity -- exactly as the app
 * feeds it a live camera, and its pitch is part of the golden line.
 */
class GuidanceSimulator(
    private val analysisConfig: AnalysisConfig = AnalysisConfig(),
    // What the app's standard runtime profile runs: sem + det, every frame.
    private val perceptionConfig: PerceptionConfig = PerceptionConfig.forProfile(
        profile = PerceptionProfile.DEFAULT,
        realtimeObstacleProviderType = ObstacleProviderType.DETECTION_MODEL,
    ),
    /** The phone settings the user would have set; the scenario's pose is the truth they approximate. */
    private val phoneGeometry: () -> PhoneGeometrySettings = { PhoneGeometrySettings.DEFAULT },
    /** Shared by the pipelines of every run, for a test that runs one scenario many times; null: each renders its own. */
    private val renders: ScenarioRenders? = null,
) {
    /** Every result of [scenario]; throws what ended the pipeline if it did not run to the last frame. */
    fun run(scenario: Scenario, depth: SimulatedDepthModel? = null): List<SceneResult> {
        val run = runToEnd(scenario, depth)
        (run.end as? SimulationEnd.Failed)?.let { throw it.error }
        return run.results
    }

    /**
     * Runs [scenario] until the pipeline ends, and returns the results it produced and how it ended:
     * after the last frame, or by throwing (the results before the throw are kept).
     *
     * A frame goes in only once the result of the one before it, if it had one, has been collected:
     * the pipeline runs on another dispatcher, and without the wait a failure could end the run while
     * an earlier result was still on its way to the collector.
     *
     * With [depth], the depth lane runs beside the pipeline on its own threads (`SimulatedPipeline.depth`), unsynchronised
     * with it: whatever it does, the results must be the same as without it.
     */
    fun runToEnd(scenario: Scenario, depth: SimulatedDepthModel? = null): SimulationRun = runBlocking {
        val pipeline = SimulatedPipeline(scenario, analysisConfig, perceptionConfig, phoneGeometry, renders = renders, depth = depth)
        val collected = Channel<Long>(Channel.UNLIMITED)
        val frames = flow {
            scenario.frames.forEachIndexed { index, frame ->
                pipeline.clock.advanceBy(scenario.stepMs)
                pipeline.stationary.value = frame.stationary
                if (frame.delivery == FrameDelivery.Lost) return@forEachIndexed
                pipeline.currentIndex = index
                val image = pipeline.cameraFrame(index)
                emit(image)
                // The frame has been processed; unless a model failed on it, its result is on its way.
                if (!pipeline.failed(image.sequenceNumber)) {
                    val got = withTimeoutOrNull(COLLECT_TIMEOUT_MS) { collected.receive() }
                        ?: error("frame ${image.sequenceNumber} did not fail, but its result never reached the collector")
                    check(got == image.sequenceNumber) { "expected the result of frame ${image.sequenceNumber}, collected $got" }
                }
            }
        }
        val results = mutableListOf<SceneResult>()
        val end = try {
            pipeline.start(frames, releaseFrame = {}, semanticMaskSnapshot = { false }, depthFrames = pipeline.depthFrames).collect {
                results += it
                collected.trySend(it.sequenceNumber)
            }
            SimulationEnd.Completed
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SimulationEnd.Failed(error)
        }
        SimulationRun(results, end)
    }

    companion object {
        /** A real-time bound on the wait for a result; only a broken run reaches it. */
        private const val COLLECT_TIMEOUT_MS = 60_000L

        /**
         * One golden line per frame: what Guidance decided and the analysis behind it. Floats are
         * rounded to 4 decimals, so a last-bit difference between JVMs does not look like a change
         * of behaviour. [distances] adds each event's distance (what decides the "near" wording) and
         * each obstacle's distance evidence: today's level, the contact's validity and interval
         * (metres, 2 decimals).
         */
        fun goldenLine(result: SceneResult, distances: Boolean = false): String {
            val d = result.debugInfo
            val events = result.events.joinToString(",") { event ->
                val params = event.messageParams.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }
                "${event.messageKey}:${event.priority.name}" + (if (params.isEmpty()) "" else "{$params}") +
                    if (distances) "@${event.distance?.name ?: "-"}" else ""
            }
            val obstacles = result.obstacles.joinToString(",") { obstacle ->
                "${obstacle.category.name}/${obstacle.zone.name}/${obstacle.distance.name}" +
                    if (distances) distanceEvidence(obstacle) else ""
            }
            return buildString {
                append("seq=").append(result.sequenceNumber)
                append(" events=[").append(events).append(']')
                append(" obstacles=[").append(obstacles).append(']')
                if (d != null) {
                    append(" ground=").append(d.groundRecognition)
                    append(" unrecognized=").append(r(d.unrecognizedGroundRatio))
                    append(" passable=").append(r(d.navigationPassableRatio))
                    append(" blockage=").append(r(d.blockageConfidence)).append('/').append(d.blockageReason)
                    append(" widthP25=").append(r(d.widthRetentionP25))
                    append(" flood=").append(r(d.floodReachRatio))
                    append(" vertical=").append(r(d.verticalReachRatio))
                }
                result.cameraGeometry?.let { geometry ->
                    append(" geometry=").append(geometry.quality.javaClass.simpleName.lowercase())
                    geometry.pitchDownDegrees?.let { append('/').append(String.format(Locale.ROOT, "%.2f", it)) }
                }
            }
        }

        private fun r(v: Float) = String.format(Locale.ROOT, "%.4f", v)

        private fun distanceEvidence(obstacle: DetectedObstacle): String {
            val e = obstacle.distanceEstimate ?: return "[none]"
            val c = e.contact
            fun m(v: Float?) = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"
            return "[today=${e.today.name} ${c.validity.name.lowercase()} ${m(c.lowerM)}..${m(c.upperM)}]"
        }
    }
}

/** What [GuidanceSimulator.runToEnd] got: every result, in order, and how the run ended. */
data class SimulationRun(val results: List<SceneResult>, val end: SimulationEnd)

/** How a simulated run ended. */
sealed interface SimulationEnd {
    /** The pipeline took every frame and completed. */
    data object Completed : SimulationEnd

    /** The pipeline threw [error] (the session ended as failed, for one). */
    data class Failed(val error: Exception) : SimulationEnd
}
