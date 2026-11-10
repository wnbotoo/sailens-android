package com.sailens.guidance.safety

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.depth.GroundContactDistanceConfig
import com.sailens.guidance.depth.usableForGroundContact
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.GeometryIssue
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.GroundRecognition
import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.motion.MotionState
import com.sailens.guidance.motion.OrientationQuality

/**
 * One frame's qualification: whether Guidance can judge the way ahead as it does today, and how each
 * optional input stood.
 *
 * The event generator decides with the baseline: its covered-lens, low-light and unrecognised-floor
 * events and the pause of the path prompts all come from [GuidanceQualifier.baseline], and the vision
 * faults the delivery protocol tells of are its projection, [BaselineQualification.visionFaults]. The
 * same baseline goes to the trace, the capture and the shell's safety state.
 *
 * @param capabilities for the trace only; no decision reads them.
 */
data class GuidanceQualification(
    val baseline: BaselineQualification,
    val capabilities: Map<GuidanceCapability, CapabilityStatus>,
) {
    companion object {
        /** Qualified, nothing reported about the optional inputs: what a result built by hand carries, like `VisionFaults.NONE`. */
        val QUALIFIED: GuidanceQualification = GuidanceQualification(BaselineQualification.Qualified, emptyMap())
    }
}

/** Whether the frame lets Guidance judge the way ahead, from the same facts the event generator reads. */
sealed interface BaselineQualification {
    /** Every judgement as usual. */
    data object Qualified : BaselineQualification

    /** Some judgements are paused, the rest go on. [reasons] is never empty. */
    data class Degraded(val reasons: Set<DegradeReason>) : BaselineQualification {
        init {
            require(reasons.isNotEmpty()) { "A degraded qualification names at least one reason" }
        }
    }

    /**
     * The picture cannot be trusted: no prompt based on it is given. [alsoDegraded] are the degradations
     * that hold in the same frame, kept so that no fact is lost while one user of it does not read it.
     */
    data class Unqualified(val reason: UnqualifiedReason, val alsoDegraded: Set<DegradeReason>) : BaselineQualification
}

/** The picture itself is unusable. Both suppress every prompt (`EventConflictResolver`), so neither is a degradation. */
enum class UnqualifiedReason { CAMERA_OBSTRUCTED, TOO_DARK }

/** A part of the judgement is paused. */
enum class DegradeReason {
    /** The floor is confirmed unrecognised: blocked, path-complex and narrowing prompts pause, obstacles go on. */
    GROUND_UNRECOGNIZED,
}

/**
 * The prompts a paused judgement keeps back, by category: the ones the event generator stops making while it holds
 * (`EventGeneratorBaselineGateTest` holds the two to the same set).
 */
val DegradeReason.pausedEventCategories: Set<EventCategory>
    get() = when (this) {
        DegradeReason.GROUND_UNRECOGNIZED -> PATH_PROMPT_CATEGORIES
    }

private val PATH_PROMPT_CATEGORIES: Set<EventCategory> = setOf(EventCategory.BLOCKED, EventCategory.PATH_COMPLEX, EventCategory.NARROWING)

/**
 * Optional inputs that have a source today. Runtime state, not the edition's static capability model
 * (`PipelineAvailability`, `CapabilityExpectations`): that one says what a build offers, this one how an
 * input stood in one frame.
 */
enum class GuidanceCapability { ORIENTATION, CAMERA_GEOMETRY, GROUND_CONTACT_DISTANCE }

sealed interface CapabilityStatus {
    /** Not wired or switched off in this session. */
    data object NotConfigured : CapabilityStatus

    data object Available : CapabilityStatus

    /** Usable, with a known weakness. */
    data class Degraded(val issues: Set<String>) : CapabilityStatus

    data class Unavailable(val issues: Set<String>) : CapabilityStatus
}

/** The vision faults this qualification stands for: lossless, and equal to what the event generator computes. */
fun BaselineQualification.visionFaults(): VisionFaults = when (this) {
    BaselineQualification.Qualified -> VisionFaults.NONE
    is BaselineQualification.Degraded -> VisionFaults(
        frameQuality = FrameQuality.OK,
        groundUnrecognized = DegradeReason.GROUND_UNRECOGNIZED in reasons,
    )
    is BaselineQualification.Unqualified -> VisionFaults(
        frameQuality = when (reason) {
            UnqualifiedReason.CAMERA_OBSTRUCTED -> FrameQuality.OBSTRUCTED
            UnqualifiedReason.TOO_DARK -> FrameQuality.TOO_DARK
        },
        groundUnrecognized = DegradeReason.GROUND_UNRECOGNIZED in alsoDegraded,
    )
}

/** Whether the path prompts (blocked, path complex, narrowing) are paused, as `GroundRecognition.pausesPathPrompts` says. */
val BaselineQualification.pausesPathPrompts: Boolean
    get() = when (this) {
        BaselineQualification.Qualified -> false
        is BaselineQualification.Degraded -> DegradeReason.GROUND_UNRECOGNIZED in reasons
        is BaselineQualification.Unqualified -> DegradeReason.GROUND_UNRECOGNIZED in alsoDegraded
    }

/** The level as the trace and the capture write it: `qualified` / `degraded` / `unqualified`. A closed set. */
val BaselineQualification.traceLevel: String
    get() = when (this) {
        BaselineQualification.Qualified -> "qualified"
        is BaselineQualification.Degraded -> "degraded"
        is BaselineQualification.Unqualified -> "unqualified"
    }

/** The reasons, lower case: the reason a frame is unqualified first, then the degradations in declaration order. */
val BaselineQualification.traceReasons: List<String>
    get() = when (this) {
        BaselineQualification.Qualified -> emptyList()
        is BaselineQualification.Degraded -> reasons.sorted().map { it.name.lowercase() }
        is BaselineQualification.Unqualified -> listOf(reason.name.lowercase()) + alsoDegraded.sorted().map { it.name.lowercase() }
    }

/** The status as the trace writes it: `not_configured` / `available` / `degraded` / `unavailable`. */
val CapabilityStatus.traceStatus: String
    get() = when (this) {
        CapabilityStatus.NotConfigured -> "not_configured"
        CapabilityStatus.Available -> "available"
        is CapabilityStatus.Degraded -> "degraded"
        is CapabilityStatus.Unavailable -> "unavailable"
    }

/** The issues, sorted. */
val CapabilityStatus.traceIssues: List<String>
    get() = when (this) {
        CapabilityStatus.NotConfigured, CapabilityStatus.Available -> emptyList()
        is CapabilityStatus.Degraded -> issues.sorted()
        is CapabilityStatus.Unavailable -> issues.sorted()
    }

/**
 * Derives the qualification. Pure: it reads the snapshot and the analysis config, never a clock, an
 * output or a setting outside them. It costs a few comparisons and small objects per frame.
 */
internal object GuidanceQualifier {

    /**
     * The same test as `EventGenerator.visionFaults`, word for word: frame quality counts only while
     * sensor-quality events are on; the floor counts once confirmed unrecognised.
     */
    fun baseline(snapshot: SceneSnapshot, config: AnalysisConfig): BaselineQualification {
        val quality = if (config.enableSensorQualityEvents) snapshot.frameQuality else FrameQuality.OK
        val degraded = if (snapshot.groundRecognition == GroundRecognition.UNRECOGNIZED) {
            setOf(DegradeReason.GROUND_UNRECOGNIZED)
        } else {
            emptySet()
        }
        return when (quality) {
            FrameQuality.OBSTRUCTED -> BaselineQualification.Unqualified(UnqualifiedReason.CAMERA_OBSTRUCTED, degraded)
            FrameQuality.TOO_DARK -> BaselineQualification.Unqualified(UnqualifiedReason.TOO_DARK, degraded)
            FrameQuality.OK -> if (degraded.isEmpty()) BaselineQualification.Qualified else BaselineQualification.Degraded(degraded)
        }
    }

    /**
     * How the optional inputs stood for one frame. Never part of the baseline.
     *
     * @param orientationConfigured false when no orientation source is wired (`NoOrientationSource`).
     */
    fun capabilities(
        geometry: CameraGeometry,
        motion: MotionState,
        orientationConfigured: Boolean,
        groundContact: GroundContactDistanceConfig,
    ): Map<GuidanceCapability, CapabilityStatus> = mapOf(
        GuidanceCapability.ORIENTATION to orientation(motion, orientationConfigured),
        GuidanceCapability.CAMERA_GEOMETRY to cameraGeometry(geometry),
        GuidanceCapability.GROUND_CONTACT_DISTANCE to groundContactDistance(geometry, groundContact),
    )

    private fun orientation(motion: MotionState, configured: Boolean): CapabilityStatus {
        if (!configured) return CapabilityStatus.NotConfigured
        return when (motion.orientationQuality) {
            OrientationQuality.OK -> CapabilityStatus.Available
            OrientationQuality.DEGRADED -> CapabilityStatus.Degraded(setOf(HEADING_CARRIED_OVER))
            OrientationQuality.UNAVAILABLE -> CapabilityStatus.Unavailable(setOf(NO_ROTATION_SAMPLE))
        }
    }

    private fun cameraGeometry(geometry: CameraGeometry): CapabilityStatus = when (val quality = geometry.quality) {
        GeometryQuality.Ok -> CapabilityStatus.Available
        is GeometryQuality.Degraded -> CapabilityStatus.Degraded(quality.issues.names())
        is GeometryQuality.Unavailable -> CapabilityStatus.Unavailable(quality.issues.names())
    }

    /**
     * Off, it is not configured. A seed height never changes a level (`DistanceFusion.fuse`), so then it
     * is unavailable whatever the geometry; so it is when the estimator cannot use the geometry. Whether
     * each obstacle's contact was valid is in the trace's obstacle distances.
     */
    private fun groundContactDistance(geometry: CameraGeometry, config: GroundContactDistanceConfig): CapabilityStatus {
        if (!config.enabled) return CapabilityStatus.NotConfigured
        if (geometry.phone.source == HeightSource.DEFAULT_SEED) return CapabilityStatus.Unavailable(setOf(HEIGHT_SEED))
        if (!usableForGroundContact(geometry)) {
            return CapabilityStatus.Unavailable(
                when (val quality = geometry.quality) {
                    GeometryQuality.Ok -> emptySet()
                    is GeometryQuality.Degraded -> quality.issues.names()
                    is GeometryQuality.Unavailable -> quality.issues.names()
                },
            )
        }
        return CapabilityStatus.Available
    }

    private fun Set<GeometryIssue>.names(): Set<String> = mapTo(LinkedHashSet()) { it.name.lowercase() }

    const val HEADING_CARRIED_OVER = "heading_carried_over"
    const val NO_ROTATION_SAMPLE = "no_rotation_sample"
    const val HEIGHT_SEED = "height_seed"
}
