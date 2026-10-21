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
import com.sailens.guidance.geometry.CameraFactsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.geometry.toCameraFacts
import com.sailens.guidance.motion.MotionTracker
import com.sailens.guidance.motion.OrientationSource
import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.perception.ObstacleModelOutput
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.model.scene.SceneResult
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

/**
 * Runs a [Scenario] through the real Guidance pipeline (`StartSceneAnalysisUseCase` and everything
 * below it) with fakes only at the model boundary (M2a, implementation doc §6). Deterministic: a
 * fake clock that advances by the scenario's step, seeded camera noise, no threads of its own.
 *
 * Wired as `DomainBindingsModule` wires the app, with two substitutions, both because the native
 * libraries are arm64-only and this runs on the JVM:
 * - connectivity uses the Kotlin fallback, not `NativeConnectivityStatsExtractor`. The two are known
 *   to differ on a narrowing corridor (the native kernel skips the perspective width scaling), so
 *   the goldens' blockage, width and flood figures are the fallback's, not the shipped kernel's;
 * - semantic stats are computed by the Kotlin extractor from the class map, where the device gets
 *   them from the fused native postprocess.
 *
 * Today's Guidance decides on no pose; the scenario's pose shapes what the models report (the class
 * map and boxes move with pitch and height). M3o's geometry is fed the scenario's own raw facts
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
    /** The phone settings the user would have set (M3a); the scenario's pose is the truth they approximate. */
    private val phoneGeometry: () -> PhoneGeometrySettings = { PhoneGeometrySettings.DEFAULT },
) {
    private val semantics = CityscapesNavigationSemantics

    fun run(scenario: Scenario): List<SceneResult> = runBlocking {
        // Rendering is the slow part, and a standing user repeats the same view frame after frame.
        val views = HashMap<Pair<Pose, World>, RenderedFrame>()
        val rendered = scenario.frames.map { views.getOrPut(it.pose to it.world) { render(scenario.camera, it.pose, it.world) } }
        val now = AtomicLong(1_000_000)
        val clock = { now.get() }
        val stationary = MutableStateFlow(false)
        var current = 0
        val cameraFacts = scenario.facts(0).camera.toCameraFacts()
        val orientation = object : OrientationSource {
            private val noRotation = MotionTracker()
            override fun start() = Unit
            override fun stop() = Unit
            override fun gravityNear(timeNanos: Long) = scenario.facts(current).gravity
                .minByOrNull { abs(it.timestampNanos - timeNanos) }
                ?.let { SensorSample(it.timestampNanos, Vec3(it.values[0], it.values[1], it.values[2])) }
            // Scenarios carry no rotation vector yet.
            override fun motionState(atNanos: Long, isStationary: Boolean) = noRotation.state(atNanos, isStationary)
        }
        fun out(frame: ImageFrame) = rendered[(frame.sequenceNumber - 1).toInt()]

        val repository = object : PerceptionRepository {
            override val isInitialized = true
            override suspend fun initialize() = Unit
            override suspend fun release() = Unit
            override suspend fun segment(frame: ImageFrame): Result<SegmentationOutput> {
                val ids = out(frame).classMap
                return Result.success(SegmentationOutput(SegmentationMask(scenario.camera.mapWidth, scenario.camera.mapHeight, ids.copyOf()), 0, 0, 0))
            }
        }
        val provider = object : ObstacleProvider {
            override val isInitialized = true
            override suspend fun initialize() = Unit
            override fun release() = Unit
            override suspend fun detect(frame: ImageFrame) = ObstacleModelOutput(out(frame).detections)
        }
        val sensors = object : DeviceSensorRepository {
            override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)
            override val deviceRotationValue = 0
            override val deviceRotationDegree = 0
            override val isStationary: StateFlow<Boolean> = stationary
            override fun startObserving() = Unit
            override fun stopObserving() = Unit
        }
        val profiles = PerceptionProfileManager(perceptionConfig)
        val processFrame = ProcessFrameUseCase(
            profileManager = profiles,
            perceptionRepository = repository,
            realtimeObstacleProvider = provider,
            depthRepository = DefaultDepthRepository(ImagePositionDepthEstimator(), GroundContactDistanceEstimator(semantics)),
            segmentationAnalyzer = SegmentationAnalyzer(analysisConfig, semantics),
            obstacleExtractor = ObstacleExtractor(perceptionConfig, semantics),
            obstacleTracker = ObstacleTracker(perceptionConfig),
            clock = clock,
        )
        val analyze = AnalyzeSceneUseCase(
            connectivityChecker = ConnectivityChecker(analysisConfig, KotlinConnectivityStatsExtractor(analysisConfig)),
            roadSafetyAnalyzer = RoadSafetyAnalyzer(analysisConfig, semantics),
            groundTypeDetector = GroundTypeDetector(analysisConfig),
            sceneClassifier = SceneClassifier(analysisConfig),
            crossValidator = CrossValidator(analysisConfig),
            obstacleOcclusionAnalyzer = ObstacleOcclusionAnalyzer(perceptionConfig),
            groundRecognitionAnalyzer = GroundRecognitionAnalyzer(analysisConfig, semantics, clock),
        )
        val decide = DecideEventsUseCase(
            EventGenerator(analysisConfig), EventConflictResolver(), EventMerger(), CooldownManager(), sensors, clock,
        )
        val start = StartSceneAnalysisUseCase(
            profileManager = profiles,
            perceptionRepository = repository,
            realtimeObstacleProvider = provider,
            processFrameUseCase = processFrame,
            analyzeSceneUseCase = analyze,
            decideEventsUseCase = decide,
            frameQualityAnalyzer = FrameQualityAnalyzer(),
            logService = SilentLog,
            traceService = NoOpTraceService,
            traceRuntimeConfig = TraceRuntimeConfig(enabled = false),
            pipelineBudget = PipelinePerformanceBudget(),
            orientationSource = orientation,
            cameraFactsSource = CameraFactsSource { cameraFacts },
            phoneGeometry = phoneGeometry,
            isStationary = { stationary.value },
        )
        val frames = flow {
            scenario.frames.forEachIndexed { index, frame ->
                now.addAndGet(scenario.stepMs)
                stationary.value = frame.stationary
                current = index
                emit(cameraFrame(scenario, index, frame.lensCovered))
            }
        }
        val results = mutableListOf<SceneResult>()
        start(frames, releaseFrame = {}, semanticMaskSnapshot = { false }).collect { results += it }
        results
    }

    /**
     * The analysis frame as the camera would deliver it: the scenario camera's buffer size, rotation,
     * timestamps and sensor-to-buffer transform (what M3o reads), and luma for the frame-quality
     * check -- textured, or flat when the lens is covered. Only the luma the check samples is filled.
     */
    private fun cameraFrame(scenario: Scenario, index: Int, covered: Boolean): ImageFrame {
        val record = scenario.facts(index).frame
        val w = record.sourceWidth
        val h = record.sourceHeight
        val random = Random(record.seq)
        val y = ByteArray(w * h) { if (covered) 6 else (110 + random.nextInt(-35, 35)).toByte() }
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

    companion object {
        /**
         * One golden line per frame: what Guidance decided and the analysis behind it. Floats are
         * rounded to 4 decimals, so a last-bit difference between JVMs does not look like a change
         * of behaviour. [distances] adds each event's distance (what decides the "near" wording) and
         * each obstacle's distance evidence (M3a): today's level, the contact's validity and interval
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
