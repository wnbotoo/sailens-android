package com.sailens.guidance.trace.regression

import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.depth.DefaultDepthRepository
import com.sailens.guidance.depth.GroundContactDistanceEstimator
import com.sailens.guidance.depth.ImagePositionDepthEstimator
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.model.analysis.ConnectivityStats
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.perception.ObstacleModelOutput
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.analysis.ConnectivityChecker
import com.sailens.guidance.processor.analysis.ConnectivityStatsExtractor
import com.sailens.guidance.processor.analysis.CrossValidator
import com.sailens.guidance.processor.analysis.FrameQualityAnalyzer
import com.sailens.guidance.processor.analysis.FrameQualityMeasurement
import com.sailens.guidance.processor.analysis.GroundRecognitionAnalyzer
import com.sailens.guidance.processor.analysis.GroundTypeDetector
import com.sailens.guidance.processor.analysis.ObstacleOcclusionAnalyzer
import com.sailens.guidance.processor.analysis.RoadSafetyAnalyzer
import com.sailens.guidance.processor.analysis.SceneClassifier
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.processor.perception.ObstacleExtractor
import com.sailens.guidance.processor.perception.ObstacleTracker
import com.sailens.guidance.processor.perception.PerceptionProfileManager
import com.sailens.guidance.processor.perception.SegmentationAnalyzer
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.repository.ObstacleProvider
import com.sailens.guidance.repository.PerceptionRepository
import com.sailens.guidance.semantics.NavigationSemantics
import com.sailens.guidance.trace.capture.ConnectivityRecord
import com.sailens.guidance.trace.capture.EventRecord
import com.sailens.guidance.trace.capture.RegressionClocks
import com.sailens.guidance.trace.capture.RegressionFrameRecord
import com.sailens.guidance.trace.capture.RegressionRevokeRecord
import com.sailens.guidance.trace.capture.SemanticRuns
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import com.sailens.guidance.usecase.perception.AnalyzeSceneUseCase
import com.sailens.guidance.usecase.perception.ProcessFrameUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Replays a model-regression record through the real decision path on the JVM (M0b exit: "replaying
 * a record reproduces the recorded events exactly").
 *
 * Real code: `ProcessFrameUseCase`, `AnalyzeSceneUseCase` and every processor in it, the
 * frame-quality debouncing, `DecideEventsUseCase` with its generator, resolver, merger and cooldown.
 * At the boundary, stand-ins serve what the device recorded: the semantic output (class map, native
 * statistics, masks rebuilt and CRC-checked), the detector output, the native connectivity statistics
 * (while their input matches), the frame-quality measurements, the motion state, the clock readings,
 * and the revocations between decisions.
 *
 * The per-frame order mirrors `StartSceneAnalysisUseCase`: process the frame, analyse the scene and
 * the frame quality, decide. A CI test records a real pipeline run and replays it, which is what
 * keeps the two in step.
 *
 * Exact means: every frame's decided events equal the recorded ones (ignoring the random ids), every
 * clock tape is used up, every recorded connectivity call is matched by an identical input, every
 * rebuilt mask has the recorded CRC. The first frame that breaks any of these ends exact replay;
 * [Report] says which and why. A frame where upstream code changed and connectivity had to be
 * computed in Kotlin is reported as approximate.
 */
class RegressionReplay(
    private val perceptionConfig: PerceptionConfig,
    private val analysisConfig: AnalysisConfig,
    private val navigationSemantics: NavigationSemantics,
) {
    data class Mismatch(val seq: Long, val what: String, val expected: String, val actual: String)

    data class Report(
        val framesReplayed: Int,
        val decisions: Int,
        /** The first divergence; null when the replay was exact up to [framesReplayed]. */
        val firstMismatch: Mismatch?,
        /** Frames whose connectivity had to be computed in Kotlin (upstream changed its input). */
        val approximateFrames: List<Long>,
        /** Frames left unreplayed because the record lost one before them. */
        val stoppedAtLostSeq: Long?,
    ) {
        val exact: Boolean get() = firstMismatch == null && approximateFrames.isEmpty()
    }

    /** What the replayed path made of one frame, for evaluations that look past the decided events. */
    data class FrameOutcome(val obstacles: List<DetectedObstacle>, val events: List<SceneEvent>)

    /** Stops the replay of one frame with a precise reason. */
    private class Divergence(val what: String, val expected: String, val actual: String) : RuntimeException(what)

    /**
     * @param classMap the class map a frame recorded (`CaptureSessionReader.classMap`).
     * @param firstLostSeq the record's `firstLostSeq`: replay stops before it.
     * @param geometryOf the camera geometry to process a frame with: by default the one it recorded
     *   (schema 1.4), none for older records. An evaluation can supply one resolved from the
     *   capture's sensor log instead.
     * @param compareDecisions false for a counterfactual run (a config the device did not run with):
     *   decisions are not compared, and a recorded revoke applies to the replayed event with the
     *   same dedupe key, if the frame decided one. Every other check stays.
     * @param onFrame each successfully processed frame's tracked obstacles and decided events.
     */
    fun replay(
        frames: List<RegressionFrameRecord>,
        revokes: List<RegressionRevokeRecord>,
        classMap: (RegressionFrameRecord) -> IntArray?,
        firstLostSeq: Long? = null,
        geometryOf: (RegressionFrameRecord) -> CameraGeometry? = { it.geometry?.toGeometry() },
        onFrame: (RegressionFrameRecord, FrameOutcome) -> Unit = { _, _ -> },
        compareDecisions: Boolean = true,
    ): Report {
        val world = World(classMap)
        val revokesByDecision = revokes.groupBy { it.decisionsBefore }
        val replayedIdOf = HashMap<String, UUID>()
        var decisions = 0
        var replayed = 0
        val approximate = mutableListOf<Long>()

        for (record in frames) {
            if (firstLostSeq != null && record.seq >= firstLostSeq) {
                return Report(replayed, decisions, null, approximate, firstLostSeq)
            }
            val outcome = try {
                world.load(record)
                val frame = record.frame()
                val result = runBlocking { world.processFrame(frame, geometryOf(record)) }
                val expectedFailure = record.failure != null
                if (result.isFailure != expectedFailure) {
                    throw Divergence("frame failure", record.failure ?: "success", result.exceptionOrNull()?.toString() ?: "success")
                }
                if (result.isSuccess) {
                    val quality = world.frameQuality.analyzeMeasured(record.quality?.measurement()).stabilized
                    val snapshot = world.analyzeScene(result.getOrThrow()).copy(frameQuality = quality)
                    revokesByDecision[decisions.toLong()]?.forEach { revoke ->
                        world.cooldown.revoke(replayedIdOf[revoke.eventId] ?: UUID.randomUUID())
                    }
                    world.stationary.value = record.stationary ?: false
                    val events = world.decide(snapshot)
                    decisions++
                    onFrame(record, FrameOutcome(result.getOrThrow().obstacles, events))
                    if (compareDecisions) {
                        compareEvents(record, events)
                        record.events.zip(events).forEach { (expected, actual) -> replayedIdOf[expected.id] = actual.id }
                    } else {
                        record.events.forEach { expected ->
                            events.firstOrNull { it.dedupeKey == expected.dedupeKey }?.let { replayedIdOf[expected.id] = it.id }
                        }
                    }
                }
                world.finishFrame()
            } catch (d: Divergence) {
                Mismatch(record.seq, d.what, d.expected, d.actual)
            }
            if (outcome is Mismatch) return Report(replayed, decisions, outcome, approximate, null)
            if (world.approximate) approximate += record.seq
            replayed++
        }
        return Report(replayed, decisions, null, approximate, null)
    }

    private fun compareEvents(record: RegressionFrameRecord, events: List<SceneEvent>) {
        val expected = record.events.map(EventRecord::withoutId)
        val actual = events.map { it.toRecord().withoutId() }
        if (expected != actual) throw Divergence("decided events", expected.toString(), actual.toString())
    }

    private fun RegressionFrameRecord.frame() = ImageFrame(
        width = width,
        height = height,
        pixelBytes = ByteArray(0),
        pixelFormat = ImagePixelFormat.YUV_420_888,
        timestamp = sensorTimestampNanos,
        rotationDegrees = rotationDegrees,
        sequenceNumber = seq,
    )

    private fun com.sailens.guidance.trace.capture.FrameQualityRecord.measurement(): FrameQualityMeasurement? {
        val mean = meanLuma ?: return null
        return FrameQualityMeasurement(mean, stdLuma ?: return null, sharpness ?: 0f)
    }

    /** One replay's components and stand-ins, built from scratch like a new Guidance session. */
    private inner class World(private val classMap: (RegressionFrameRecord) -> IntArray?) {
        private lateinit var record: RegressionFrameRecord
        private val tapes = mapOf(
            RegressionClocks.PERCEPTION to Tape(RegressionClocks.PERCEPTION),
            RegressionClocks.GROUND to Tape(RegressionClocks.GROUND),
            RegressionClocks.DECIDE to Tape(RegressionClocks.DECIDE),
        )
        private val connectivityCalls = ArrayDeque<ConnectivityRecord>()
        var approximate = false
            private set

        val stationary = MutableStateFlow(false)
        val cooldown = CooldownManager()
        val frameQuality = FrameQualityAnalyzer()

        private val repository = object : PerceptionRepository {
            override val isInitialized: Boolean = true
            override suspend fun initialize() = Unit
            override suspend fun release() = Unit
            override suspend fun segment(frame: ImageFrame): Result<SegmentationOutput> = semanticOutput()
        }

        private val obstacles = object : ObstacleProvider {
            override val isInitialized: Boolean = true
            override suspend fun initialize() = Unit
            override fun release() = Unit
            override suspend fun detect(frame: ImageFrame): ObstacleModelOutput {
                val run = record.detection ?: throw Divergence("detector run", "not run", "run")
                run.failure?.let { throw ReplayedFailure(it) }
                return ObstacleModelOutput(run.detections.map { it.toObstacleDetection() })
            }
        }

        private val sensors = object : DeviceSensorRepository {
            override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)
            override val deviceRotationValue: Int = 0
            override val deviceRotationDegree: Int = 0
            override val isStationary: StateFlow<Boolean> = stationary
            override fun startObserving() = Unit
            override fun stopObserving() = Unit
        }

        private val connectivityExtractor = object : ConnectivityStatsExtractor {
            override fun extract(passableMask: BinaryMask): ConnectivityStats? {
                val recorded = connectivityCalls.removeFirstOrNull()
                    ?: throw Divergence("connectivity calls", "no more", "one more")
                if (passableMask.width != recorded.width || passableMask.height != recorded.height ||
                    passableMask.regressionCrc32() != recorded.inputCrc32
                ) {
                    // Upstream code changed this input: compute in Kotlin (ConnectivityChecker's own
                    // fallback), knowing the device's native kernel may differ.
                    approximate = true
                    return null
                }
                return recorded.stats?.toStats()
            }
        }

        private val profiles = PerceptionProfileManager(initialConfig = perceptionConfig)
        val processFrame = ProcessFrameUseCase(
            profileManager = profiles,
            perceptionRepository = repository,
            realtimeObstacleProvider = obstacles,
            depthRepository = DefaultDepthRepository(ImagePositionDepthEstimator(), GroundContactDistanceEstimator(navigationSemantics)),
            segmentationAnalyzer = SegmentationAnalyzer(config = analysisConfig, navigationSemantics = navigationSemantics),
            obstacleExtractor = ObstacleExtractor(config = perceptionConfig, navigationSemantics = navigationSemantics),
            obstacleTracker = ObstacleTracker(config = perceptionConfig),
            clock = tapes.getValue(RegressionClocks.PERCEPTION)::next,
        )
        val analyzeScene = AnalyzeSceneUseCase(
            connectivityChecker = ConnectivityChecker(config = analysisConfig, statsExtractor = connectivityExtractor),
            roadSafetyAnalyzer = RoadSafetyAnalyzer(config = analysisConfig, navigationSemantics = navigationSemantics),
            groundTypeDetector = GroundTypeDetector(config = analysisConfig),
            sceneClassifier = SceneClassifier(config = analysisConfig),
            crossValidator = CrossValidator(config = analysisConfig),
            obstacleOcclusionAnalyzer = ObstacleOcclusionAnalyzer(config = perceptionConfig),
            groundRecognitionAnalyzer = GroundRecognitionAnalyzer(
                config = analysisConfig,
                navigationSemantics = navigationSemantics,
                clock = tapes.getValue(RegressionClocks.GROUND)::next,
            ),
        )
        val decide = DecideEventsUseCase(
            eventGenerator = EventGenerator(config = analysisConfig),
            conflictResolver = EventConflictResolver(),
            eventMerger = EventMerger(),
            cooldownManager = cooldown,
            deviceSensorRepository = sensors,
            clock = tapes.getValue(RegressionClocks.DECIDE)::next,
        )

        fun load(next: RegressionFrameRecord) {
            record = next
            approximate = false
            tapes.forEach { (component, tape) -> tape.load(next.clocks[component].orEmpty()) }
            connectivityCalls.clear()
            connectivityCalls.addAll(next.connectivity)
        }

        /** Everything the frame recorded must have been used. */
        fun finishFrame() {
            tapes.values.forEach { it.checkUsedUp() }
            if (connectivityCalls.isNotEmpty()) {
                throw Divergence("connectivity calls", "${connectivityCalls.size} more", "none")
            }
        }

        private fun semanticOutput(): Result<SegmentationOutput> {
            val semantic = record.semantic ?: throw Divergence("semantic run", "none", "ran")
            return when (semantic.run) {
                SemanticRuns.FAILED -> Result.failure(ReplayedFailure(semantic.failure ?: "failed"))
                SemanticRuns.CACHED -> throw Divergence("semantic run", "cached", "ran")
                else -> {
                    val ref = semantic.classMap ?: throw Divergence("class map", "recorded", "missing")
                    val ids = classMap(record) ?: throw Divergence("class map", "readable", "unreadable")
                    val mask = SegmentationMask(ref.width, ref.height, ids)
                    val stats = semantic.stats?.let { recorded ->
                        val (passable, obstacle) = rebuildSemanticMasks(ids, ref.width, ref.height, navigationSemantics)
                        try {
                            recorded.toStats(passable, obstacle)
                        } catch (e: IllegalStateException) {
                            throw Divergence("rebuilt masks", "recorded CRC", e.message ?: "different")
                        }
                    }
                    Result.success(SegmentationOutput(mask = mask, preprocessTimeMs = 0, inferenceTimeMs = 0, postprocessTimeMs = 0, analysisStats = stats))
                }
            }
        }
    }

    /** A recorded failure, raised again where the device raised it. */
    class ReplayedFailure(message: String) : RuntimeException(message)

    /** One injected clock's recorded readings for the frame being replayed. */
    private class Tape(private val component: String) {
        private var values: List<Long> = emptyList()
        private var next = 0

        fun load(recorded: List<Long>) {
            values = recorded
            next = 0
        }

        fun next(): Long {
            if (next >= values.size) throw Divergence("$component clock reads", "${values.size}", "more")
            return values[next++]
        }

        fun checkUsedUp() {
            if (next != values.size) throw Divergence("$component clock reads", "${values.size}", "$next")
        }
    }
}

/**
 * The passable and obstacle masks of a class map, as the native fused pass derives them: a lookup of
 * each pixel's class through [semantics]. Replay checks the result against the recorded CRCs.
 */
fun rebuildSemanticMasks(classIds: IntArray, width: Int, height: Int, semantics: NavigationSemantics): Pair<BinaryMask, BinaryMask> {
    val passable = BinaryMask(width, height)
    val obstacle = BinaryMask(width, height)
    val isPassable = BooleanArray(semantics.classCount) { semantics.isPassable(it) }
    val isObstacle = BooleanArray(semantics.classCount) { semantics.isObstacle(it) }
    for (y in 0 until height) {
        for (x in 0 until width) {
            val id = classIds[y * width + x]
            if (id in isPassable.indices) {
                if (isPassable[id]) passable.set(x, y, true)
                if (isObstacle[id]) obstacle.set(x, y, true)
            }
        }
    }
    return passable to obstacle
}

