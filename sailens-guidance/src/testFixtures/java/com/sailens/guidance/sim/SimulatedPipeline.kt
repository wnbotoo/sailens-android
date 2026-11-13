package com.sailens.guidance.sim

import com.sailens.core.frame.FrameSourceGeometry
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.frame.Yuv420FrameData
import com.sailens.core.frame.YuvPlaneData
import com.sailens.core.log.LogService
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.config.PipelinePerformanceBudget
import com.sailens.guidance.config.TraceRuntimeConfig
import com.sailens.guidance.depth.DefaultDepthRepository
import com.sailens.guidance.depth.GroundContactDistanceEstimator
import com.sailens.guidance.depth.ImagePositionDepthEstimator
import com.sailens.guidance.depth.geometry.DepthEvidenceRecorder
import com.sailens.guidance.depth.geometry.DepthGeometryConfig
import com.sailens.guidance.depth.geometry.DepthGeometryLane
import com.sailens.guidance.depth.geometry.DepthGeometrySwitch
import com.sailens.guidance.depth.geometry.DepthModelCheckResult
import com.sailens.guidance.depth.geometry.DepthPreparedInput
import com.sailens.guidance.depth.geometry.DepthProducer
import com.sailens.guidance.depth.geometry.ThermalLevel
import com.sailens.guidance.geometry.CameraFactsSource
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.geometry.toCameraFacts
import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.model.perception.ObstacleModelOutput
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.motion.MotionTracker
import com.sailens.guidance.motion.OrientationSource
import com.sailens.guidance.processor.analysis.ConnectivityChecker
import com.sailens.guidance.processor.analysis.CrossValidator
import com.sailens.guidance.processor.analysis.FrameQualityAnalyzer
import com.sailens.guidance.processor.analysis.GroundRecognitionAnalyzer
import com.sailens.guidance.processor.analysis.GroundTypeDetector
import com.sailens.guidance.processor.analysis.KotlinConnectivityStatsExtractor
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
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import com.sailens.guidance.trace.NoOpTraceService
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import com.sailens.guidance.usecase.perception.AnalyzeSceneUseCase
import com.sailens.guidance.usecase.perception.ProcessFrameUseCase
import com.sailens.guidance.usecase.scene.StartSceneAnalysisUseCase
import com.sailens.guidance.usecase.scene.StopSceneAnalysisUseCase
import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.runtime.accelerator.AcceleratorCoordinator
import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DepthMap
import com.sailens.vision.depth.DepthRun
import com.sailens.vision.depth.DepthTimes
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.ResizePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

/**
 * The real Guidance pipeline for one [scenario], wired as `DomainBindingsModule` wires the app
 * (docs/geometry.md §7): `StartSceneAnalysisUseCase` and everything below it, with fakes only at the
 * model boundary, a clock and a stationary flag that the caller moves, and the processors and
 * [cooldownManager] held here, so a caller can also stop the session with [stop] and report back to
 * the same cooldown.
 *
 * Two substitutions, both because the native libraries are arm64-only and this runs on the JVM:
 * - connectivity uses the Kotlin fallback, not `NativeConnectivityStatsExtractor`. The two are known
 *   to differ on a narrowing corridor (the native kernel skips the perspective width scaling), so
 *   blockage, width and flood figures are the fallback's, not the shipped kernel's;
 * - semantic stats are computed by the Kotlin extractor from the class map, where the device gets
 *   them from the fused native postprocess.
 *
 * The models report what [render] makes of each frame's pose and world (or the frame's own
 * [ScenarioFrame.semWorld] / [ScenarioFrame.detWorld]); frames are rendered here, up front, once per
 * distinct view. A frame frozen on an earlier one gets that frame's picture. [ScenarioFrame.semFails]
 * and [ScenarioFrame.detFails] make the model fail when it runs on that frame.
 *
 * @param semanticInit holds the segmentation model's initialisation; null: initialised from the start,
 *   so the pipeline never initialises it.
 * @param detectionInit the same for the detector.
 * @param renders keeps the rendering of the last scenario across pipelines, for a test that runs one scenario many
 *   times in a row; null: this pipeline renders its own.
 * @param depth a depth model for the depth lane (docs/geometry.md §6), run through the real lane and ground fit; null:
 *   no lane, as in a host that has none. The lane sees every frame the segmentation model is given, as a second
 *   subscription to the camera would, on its own threads and at its own pace: nothing waits for it.
 */
class SimulatedPipeline(
    val scenario: Scenario,
    analysisConfig: AnalysisConfig = AnalysisConfig(),
    // What the app's standard runtime profile runs: sem + det, every frame.
    perceptionConfig: PerceptionConfig = PerceptionConfig.forProfile(
        profile = PerceptionProfile.DEFAULT,
        realtimeObstacleProviderType = ObstacleProviderType.DETECTION_MODEL,
    ),
    /** The phone settings the user would have set; the scenario's pose is the truth they approximate. */
    phoneGeometry: () -> PhoneGeometrySettings = { PhoneGeometrySettings.DEFAULT },
    semanticInit: ModelInitGate? = null,
    detectionInit: ModelInitGate? = null,
    renders: ScenarioRenders? = null,
    val depth: SimulatedDepthModel? = null,
    /** The lane's fitter for each depth session; the lane's own default unless a test watches it. */
    depthFitter: () -> GroundFitter = { GroundFitter() },
    /** The lane's depth evidence recorder; none unless a test watches the runs. */
    depthEvidence: DepthEvidenceRecorder = DepthEvidenceRecorder.None,
) {
    private val semantics = CityscapesNavigationSemantics

    /** The pipeline's monotonic clock; it starts at [Scenario.CLOCK_START_MS]. */
    val clock = SimulatedClock(Scenario.CLOCK_START_MS)

    /** What the device's motion sensors say. */
    val stationary = MutableStateFlow(false)

    /** The scenario frame whose gravity the device's sensors report. */
    @Volatile
    var currentIndex: Int = 0

    /** The frame each frame's picture comes from: its own, or the one it is frozen on. */
    private val contentIndex = IntArray(scenario.frames.size).also { content ->
        scenario.frames.forEachIndexed { index, frame ->
            val delivery = frame.delivery
            content[index] = if (delivery is FrameDelivery.Frozen) content[delivery.fromIndex] else index
        }
    }
    private val classMaps: Array<IntArray?>
    private val detections: Array<List<ObstacleDetection>?>

    init {
        val rendered = (renders ?: ScenarioRenders()).of(scenario, contentIndex)
        classMaps = rendered.first
        detections = rendered.second
    }

    private val failedFrames = ConcurrentHashMap.newKeySet<Long>()

    /** Whether frame [sequenceNumber] failed in a model, so the pipeline dropped it. */
    fun failed(sequenceNumber: Long): Boolean = sequenceNumber in failedFrames

    private fun indexOf(frame: ImageFrame) = (frame.sequenceNumber - 1).toInt()

    /** The segmentation model. */
    val segmentation = SimulatedSegmentation(semanticInit)

    /** The detector. */
    val detector = SimulatedDetector(detectionInit)

    inner class SimulatedSegmentation internal constructor(private val gate: ModelInitGate?) : PerceptionRepository {
        /** How many times the pipeline asked it to initialise. */
        val initializations = AtomicInteger()

        /** How many times the pipeline released it. */
        val releases = AtomicInteger()
        override val isInitialized: Boolean get() = gate?.initialized ?: true
        override suspend fun initialize() {
            initializations.incrementAndGet()
            gate?.await()
        }
        override suspend fun release() {
            releases.incrementAndGet()
        }
        override suspend fun segment(frame: ImageFrame): Result<SegmentationOutput> {
            if (depth != null) depthChannel.trySend(frame)
            val index = indexOf(frame)
            if (scenario.frames[index].semFails) {
                failedFrames += frame.sequenceNumber
                return Result.failure(SimulatedModelFailure("segmentation", frame.sequenceNumber))
            }
            val ids = checkNotNull(classMaps[index]) { "frame ${frame.sequenceNumber} was not rendered" }
            return Result.success(SegmentationOutput(SegmentationMask(scenario.camera.mapWidth, scenario.camera.mapHeight, ids.copyOf()), 0, 0, 0))
        }
    }

    inner class SimulatedDetector internal constructor(private val gate: ModelInitGate?) : ObstacleProvider {
        /** How many times the pipeline asked it to initialise. */
        val initializations = AtomicInteger()

        /** How many times the pipeline released it. */
        val releases = AtomicInteger()
        override val isInitialized: Boolean get() = gate?.initialized ?: true
        override suspend fun initialize() {
            initializations.incrementAndGet()
            gate?.await()
        }
        override fun release() {
            releases.incrementAndGet()
        }
        override suspend fun detect(frame: ImageFrame): ObstacleModelOutput {
            val index = indexOf(frame)
            if (scenario.frames[index].detFails) {
                failedFrames += frame.sequenceNumber
                throw SimulatedModelFailure("detection", frame.sequenceNumber)
            }
            return ObstacleModelOutput(checkNotNull(detections[index]) { "frame ${frame.sequenceNumber} was not rendered" })
        }
    }

    private val cameraFacts = scenario.facts(0).camera.toCameraFacts()

    private val orientation = object : OrientationSource {
        private val noRotation = MotionTracker()
        override fun start() = Unit
        override fun stop() = Unit
        override fun gravityNear(timeNanos: Long) = scenario.facts(currentIndex).gravity
            .minByOrNull { abs(it.timestampNanos - timeNanos) }
            ?.let { SensorSample(it.timestampNanos, Vec3(it.values[0], it.values[1], it.values[2])) }
        // Scenarios carry no rotation vector yet.
        override fun motionState(atNanos: Long, isStationary: Boolean) = noRotation.state(atNanos, isStationary)
    }

    private val sensors = object : DeviceSensorRepository {
        override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)
        override val deviceRotationValue = 0
        override val deviceRotationDegree = 0
        override val isStationary: StateFlow<Boolean> = stationary
        override fun startObserving() = Unit
        override fun stopObserving() = Unit
    }

    val profiles = PerceptionProfileManager(perceptionConfig)
    val segmentationAnalyzer = SegmentationAnalyzer(analysisConfig, semantics)
    val obstacleTracker = ObstacleTracker(perceptionConfig)
    val processFrame = ProcessFrameUseCase(
        profileManager = profiles,
        perceptionRepository = segmentation,
        realtimeObstacleProvider = detector,
        depthRepository = DefaultDepthRepository(ImagePositionDepthEstimator(), GroundContactDistanceEstimator(semantics)),
        segmentationAnalyzer = segmentationAnalyzer,
        obstacleExtractor = ObstacleExtractor(perceptionConfig, semantics),
        obstacleTracker = obstacleTracker,
        clock = clock,
    )
    val connectivityChecker = ConnectivityChecker(analysisConfig, KotlinConnectivityStatsExtractor(analysisConfig))
    val roadSafetyAnalyzer = RoadSafetyAnalyzer(analysisConfig, semantics)
    val groundTypeDetector = GroundTypeDetector(analysisConfig)
    val sceneClassifier = SceneClassifier(analysisConfig)
    val groundRecognitionAnalyzer = GroundRecognitionAnalyzer(analysisConfig, semantics, clock)
    val analyze = AnalyzeSceneUseCase(
        connectivityChecker = connectivityChecker,
        roadSafetyAnalyzer = roadSafetyAnalyzer,
        groundTypeDetector = groundTypeDetector,
        sceneClassifier = sceneClassifier,
        crossValidator = CrossValidator(analysisConfig),
        obstacleOcclusionAnalyzer = ObstacleOcclusionAnalyzer(perceptionConfig),
        groundRecognitionAnalyzer = groundRecognitionAnalyzer,
    )
    val cooldownManager = analysisConfig.detFirst.let {
        CooldownManager(
            proximityEscalation = it.proximityEscalation,
            retryInterrupted = it.retryInterrupted,
            metricOnlyRepeatMs = it.metricGate?.metricOnlyRepeatMs ?: 6_000L,
        )
    }
    val decide = DecideEventsUseCase(
        EventGenerator(analysisConfig),
        EventConflictResolver(analysisConfig.detFirst.keepBlockedWithObstacle),
        EventMerger(),
        cooldownManager,
        sensors,
        clock,
    )
    val frameQualityAnalyzer = FrameQualityAnalyzer()

    private val depthChannel = Channel<ImageFrame>(Channel.CONFLATED)

    /** The depth lane's frames, for `StartSceneAnalysisUseCase`'s `depthFrames`; null without [depth]. */
    val depthFrames: ((Long) -> Flow<ImageFrame>)? = depth?.let { { _ -> depthChannel.receiveAsFlow() } }

    /** Every depth runner the lane built, in order: one per session that got past its model check. */
    val depthRunners: MutableList<SimulatedDepthRunner> = java.util.Collections.synchronizedList(mutableListOf())

    /** The depth lane, wired as `GuidanceModule` wires it; null without [depth]. */
    val depthLane: DepthGeometryLane? = depth?.let { model ->
        DepthGeometryLane(
            producerFactory = { SimulatedDepthRunner(model).also { depthRunners += it } },
            modelCheck = {
                if (model.checkThrows) throw IllegalStateException("simulated depth model check failure")
                DepthModelCheckResult.Compatible
            },
            coordinator = AcceleratorCoordinator(),
            thermal = { model.thermal },
            traceService = NoOpTraceService,
            config = DepthGeometryConfig(),
            logService = SilentLog,
            fitterFactory = depthFitter,
            elapsedMs = clock,
            evidence = depthEvidence,
        )
    }

    /**
     * The depth model for [depth]: the disparity [renderDisparity] gives each frame's own pose and world, `a/Z + b`,
     * at the model's size and with the letterbox the contract fixes.
     */
    inner class SimulatedDepthRunner internal constructor(private val model: SimulatedDepthModel) : DepthProducer {
        /** Runs that reached the model. */
        val inferences = AtomicInteger()

        override val needsAccelerator: Boolean get() = true

        override suspend fun initialize() {
            if (model.initFails) throw UnsatisfiedLinkError("simulated depth model initialization failure")
        }

        override suspend fun prepare(frame: ImageFrame): DepthPreparedInput {
            val rotated = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
            val transform = DepthInputTransform.create(
                if (rotated) frame.height else frame.width,
                if (rotated) frame.width else frame.height,
                model.width,
                model.height,
                ResizePolicy.PAD_KEEP_ASPECT,
            )
            return DepthPreparedInput(transform, detail = null, preprocessMs = 0, payload = Prepared(contentIndex[indexOf(frame)], frame.timestamp))
        }

        private inner class Prepared(val content: Int, val timestampNanos: Long)

        override suspend fun infer(input: DepthPreparedInput): DepthRun<DepthMap> {
            inferences.incrementAndGet()
            val prepared = input.payload as Prepared
            val content = scenario.frames[prepared.content]
            val t = input.transform
            var values = renderDisparity(
                scenario.camera, content.pose, content.world, model.width, model.height, t.analysisWidth, t.analysisHeight,
                { x, y -> t.toAnalysisX(x) to t.toAnalysisY(y) },
            )
            // A model that outputs depth: Z = a / (d - b), the sky capped far away.
            if (model.inverted) values = FloatArray(values.size) { i -> if (values[i] > 0.2001f) (10f / (values[i] - 0.2f)).coerceAtMost(200f) else 200f }
            return DepthRun(
                DisparityMap(model.width, model.height, values, t, DepthTimes.ofFrame(prepared.timestampNanos)), nonFiniteCount = 0, detail = null, preprocessMs = 0,
                inferenceMs = 0, outputReadMs = 0, runtimeInfo = MlRuntimeInfo("simulated", "simulated", "simulated", "kotlin_ground_fit"),
                shapeLabel = "1x3x${model.height}x${model.width}>1x${model.height}x${model.width}",
            )
        }

        override suspend fun release() = Unit
    }

    /** Starts a session: the pipeline the app's Guidance screen collects. */
    val start = StartSceneAnalysisUseCase(
        profileManager = profiles,
        perceptionRepository = segmentation,
        realtimeObstacleProvider = detector,
        processFrameUseCase = processFrame,
        analyzeSceneUseCase = analyze,
        decideEventsUseCase = decide,
        frameQualityAnalyzer = frameQualityAnalyzer,
        logService = SilentLog,
        traceService = NoOpTraceService,
        traceRuntimeConfig = TraceRuntimeConfig(enabled = false),
        pipelineBudget = PipelinePerformanceBudget(),
        orientationSource = orientation,
        cameraFactsSource = CameraFactsSource { cameraFacts },
        phoneGeometry = phoneGeometry,
        isStationary = { stationary.value },
        depthLane = depthLane,
        depthSwitch = DepthGeometrySwitch(initial = depth != null),
    )

    /** Stops a session (resets every processor and the cooldown) and releases the models. */
    val stop = StopSceneAnalysisUseCase(
        perceptionRepository = segmentation,
        realtimeObstacleProvider = detector,
        processFrameUseCase = processFrame,
        segmentationAnalyzer = segmentationAnalyzer,
        obstacleTracker = obstacleTracker,
        connectivityChecker = connectivityChecker,
        roadSafetyAnalyzer = roadSafetyAnalyzer,
        groundTypeDetector = groundTypeDetector,
        sceneClassifier = sceneClassifier,
        frameQualityAnalyzer = frameQualityAnalyzer,
        groundRecognitionAnalyzer = groundRecognitionAnalyzer,
        cooldownManager = cooldownManager,
        logService = SilentLog,
    )

    /**
     * Frame [index] as the camera would deliver it: the scenario camera's buffer size, rotation,
     * timestamps and sensor-to-buffer transform (what the camera geometry reads), and luma for the
     * frame-quality check -- textured, flat when the lens is covered, or dark. Only the luma the check
     * samples is filled. A frozen frame carries its own timestamps and the luma of the frame it is
     * frozen on.
     */
    fun cameraFrame(index: Int): ImageFrame {
        val record = scenario.facts(index).frame
        val content = contentIndex[index]
        val w = record.sourceWidth
        val h = record.sourceHeight
        // Seeded by the sequence number of the frame whose picture this is.
        val random = Random(content + 1L)
        val y = when (scenario.frames[content].effectiveLighting) {
            Lighting.NORMAL -> ByteArray(w * h) { (110 + random.nextInt(-35, 35)).toByte() }
            Lighting.COVERED -> ByteArray(w * h) { 6 }
            Lighting.DARK -> ByteArray(w * h) { (20 + random.nextInt(-17, 18)).toByte() }
        }
        val chroma = YuvPlaneData(ByteArray(w * h / 2), w, 2)
        return ImageFrame(
            w, h, ByteArray(0), ImagePixelFormat.YUV_420_888, record.sensorTimestampNanos, record.rotationDegrees, record.seq,
            yuvData = Yuv420FrameData(YuvPlaneData(y, w, 1), chroma, chroma),
            receivedElapsedRealtimeNanos = record.receivedElapsedRealtimeNanos,
            sourceGeometry = FrameSourceGeometry(checkNotNull(record.sensorToBufferTransform), 0, 0, w, h),
        )
    }

    private object SilentLog : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }
}

/**
 * Each frame's class map and detections, rendered once per distinct view, and the last scenario's kept (by identity)
 * for the next pipeline given the same instance. Rendering is the slow part of a simulated run. Nothing writes to
 * them: the segmentation hands out a copy of the class map, and the detections are a read-only list.
 */
class ScenarioRenders {
    private var last: Triple<Scenario, Array<IntArray?>, Array<List<ObstacleDetection>?>>? = null

    @Synchronized
    fun of(scenario: Scenario, contentIndex: IntArray): Pair<Array<IntArray?>, Array<List<ObstacleDetection>?>> {
        last?.takeIf { it.first === scenario }?.let { return it.second to it.third }
        val classMaps = arrayOfNulls<IntArray>(scenario.frames.size)
        val detections = arrayOfNulls<List<ObstacleDetection>>(scenario.frames.size)
        // A standing user repeats the same view frame after frame.
        val views = HashMap<Pair<Pose, World>, RenderedFrame>()
        fun view(pose: Pose, world: World) = views.getOrPut(pose to world) { render(scenario.camera, pose, world) }
        scenario.frames.forEachIndexed { index, frame ->
            if (frame.delivery == FrameDelivery.Lost) return@forEachIndexed
            val content = scenario.frames[contentIndex[index]]
            classMaps[index] = view(content.pose, content.semWorld ?: content.world).classMap
            detections[index] = view(content.pose, content.detWorld ?: content.world).detections
        }
        last = Triple(scenario, classMaps, detections)
        return classMaps to detections
    }
}

/**
 * The simulator's depth model: its input size (portrait, 9:16 here, so the letterbox is small), whether it outputs
 * depth instead of disparity, whether its static check or its initialization throws, and the device's thermal status
 * the lane sees.
 */
data class SimulatedDepthModel(
    val width: Int = 126,
    val height: Int = 224,
    val inverted: Boolean = false,
    val checkThrows: Boolean = false,
    val initFails: Boolean = false,
    val thermal: ThermalLevel = ThermalLevel.NONE,
)

/** A model failing on one frame, as a scenario scripted it. */
class SimulatedModelFailure(model: String, sequenceNumber: Long) : RuntimeException("simulated $model failure on frame $sequenceNumber")

/** The pipeline's monotonic clock, milliseconds. Only the caller moves it. */
class SimulatedClock(startMs: Long) : () -> Long {
    private val now = AtomicLong(startMs)

    override fun invoke(): Long = now.get()

    fun advanceBy(ms: Long) {
        now.addAndGet(ms)
    }
}

/**
 * Holds a simulated model's initialisation until the caller says how it ends: [release] (it
 * succeeds) or [fail] (it throws). Until then the model is not initialised and `initialize()` waits.
 *
 * @param cancellable false: a waiting `initialize()` ignores cancellation until released or failed, as
 *   a model load that cannot be interrupted.
 * @param onWaiting told, on the pipeline's thread, each time `initialize()` starts waiting, before it
 *   waits: a caller that drives time can tell a pipeline held here from one still on its way.
 */
class ModelInitGate(val cancellable: Boolean = true, private val onWaiting: () -> Unit = {}) {
    private val outcome = CompletableDeferred<Unit>()

    /** Whether the initialisation has succeeded. */
    @Volatile
    var initialized: Boolean = false
        private set

    /** How many times `initialize()` has started waiting. */
    val waits = AtomicInteger()

    fun release() {
        outcome.complete(Unit)
    }

    fun fail(cause: Throwable) {
        outcome.completeExceptionally(cause)
    }

    internal suspend fun await() {
        waits.incrementAndGet()
        onWaiting()
        if (cancellable) outcome.await() else withContext(NonCancellable) { outcome.await() }
        initialized = true
    }
}
