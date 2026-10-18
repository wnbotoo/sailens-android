package com.sailens.guidance.depth

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.GeometryIssue
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.semantics.NavigationSemantics
import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Ground-contact distance (M3a, implementation doc §8). Part of [com.sailens.guidance.config.PerceptionConfig],
 * so a model-regression capture records it with the rest of the session's config.
 *
 * Off by default. Even when [enabled], a height that is only the runtime seed
 * ([HeightSource.DEFAULT_SEED]) never changes a distance level: enabling needs a placement preset or
 * a body-height calibration. Off or not, the estimate is computed and traced, so field traces show
 * what it would have done.
 *
 * None of these values is calibrated against field data yet; they are the proposals of §8.
 */
@Serializable
data class GroundContactDistanceConfig(
    val enabled: Boolean = false,
    /** The level thresholds, metres, applied to the interval's lower bound (today's hardware-depth thresholds). */
    val nearMeters: Float = 1.5f,
    val mediumMeters: Float = 4.0f,
    /** Relative phone-height error for a seed or a placement preset. */
    val presetHeightTolerance: Float = 0.15f,
    /** Relative phone-height error once calibrated from body height. */
    val calibratedHeightTolerance: Float = 0.10f,
    /** Gravity direction error, degrees. */
    val gravityToleranceDeg: Float = 2f,
    /**
     * A contact ray less than this far below the horizon is [ContactValidity.NEAR_HORIZON]: the
     * distance is ill-conditioned there. Larger than [gravityToleranceDeg], so the upper bound stays finite.
     */
    val minDepressionDeg: Float = 3f,
    /** A box whose bottom is this close to the image's bottom edge is [ContactValidity.CLIPPED]. */
    val clippedBottomMargin: Float = 0.01f,
    /** Another box whose bottom is lower by more than this covers the contact ([ContactValidity.OCCLUDED]). */
    val occluderNearerMargin: Float = 0.02f,
    /** Height, as a fraction of the frame, of the strip below the contact that must show ground. */
    val groundProbeHeight: Float = 0.02f,
    /** Fraction of that strip the semantic map must read as ground. */
    val minGroundBelowRatio: Float = 0.3f,
)

/** Whether a box's bottom centre can be trusted as the point where the object meets the ground. */
enum class ContactValidity {
    VALID,

    /** No usable camera geometry for the frame (or stale gravity). */
    NO_GEOMETRY,

    /** The box reaches the bottom edge: the true contact is below the frame, at least as near as the edge. */
    CLIPPED,

    /** The contact ray is at or near the horizon: the distance is ill-conditioned. */
    NEAR_HORIZON,

    /** A nearer detection covers the contact (a bench, a car, another person in front). */
    OCCLUDED,

    /** The semantic map shows no ground just below the contact (legs behind a railing, an unrecognised floor). */
    GROUND_UNSEEN,

    /** The box's implied height does not fit its class at the computed distance. */
    IMPLAUSIBLE_BOX,
}

/**
 * The ground-contact estimate of one box. Horizontal distances in metres from the camera's
 * projection onto the ground. [lowerM]..[upperM] spans the height and gravity tolerances; [upperM]
 * is null when unbounded. The interval is present whenever the geometry allowed computing it, even
 * for an invalid contact -- the trace shows it; only [ContactValidity.VALID] may change a level.
 */
data class GroundContactEstimate(
    val validity: ContactValidity,
    val lowerM: Float? = null,
    val pointM: Float? = null,
    val upperM: Float? = null,
)

/**
 * A box's distance: today's level from the image position, the ground-contact estimate, and the
 * level used. [used] is never farther than [today] (risk-conservative fusion, §8).
 */
data class DistanceEstimate(
    val used: DistanceLevel,
    val today: DistanceLevel,
    val contact: GroundContactEstimate,
    /** The level of the contact interval's lower bound; null without an interval. */
    val contactLevel: DistanceLevel?,
) {
    init {
        require(used.value <= today.value) { "fusion made $today into the farther $used" }
    }

    companion object {
        /** Today's estimate alone, for paths that compute no contact. */
        fun today(level: DistanceLevel) = DistanceEstimate(level, level, GroundContactEstimate(ContactValidity.NO_GEOMETRY), null)
    }
}

/** What a frame offers the distance estimate besides the box itself. */
data class DistanceFrame(
    val geometry: CameraGeometry?,
    /** The frame's other detections, for the occlusion check. */
    val otherBoxes: List<NormalizedRect> = emptyList(),
    /** The semantic class map of the frame (camera frame only), for the ground check. */
    val segmentation: SegmentationMask? = null,
    val config: GroundContactDistanceConfig = GroundContactDistanceConfig(),
) {
    companion object {
        val NONE = DistanceFrame(geometry = null)
    }
}

/** The level fusion of §8, separate so its table can be tested exhaustively. */
object DistanceFusion {

    fun nearerOf(a: DistanceLevel, b: DistanceLevel): DistanceLevel = if (a.value <= b.value) a else b

    /**
     * The level used: the nearer of [today] and [contactLevel] when the estimate is enabled, the
     * height is more than the seed and the contact is valid; [today] otherwise. It can keep or raise
     * risk, never lower it.
     */
    fun fuse(
        today: DistanceLevel,
        contact: GroundContactEstimate,
        contactLevel: DistanceLevel?,
        enabled: Boolean,
        heightSource: HeightSource?,
    ): DistanceLevel {
        if (!enabled || heightSource == null || heightSource == HeightSource.DEFAULT_SEED) return today
        if (contact.validity != ContactValidity.VALID || contactLevel == null) return today
        return nearerOf(today, contactLevel)
    }

    fun level(meters: Float, config: GroundContactDistanceConfig): DistanceLevel = when {
        meters < config.nearMeters -> DistanceLevel.NEAR
        meters < config.mediumMeters -> DistanceLevel.MEDIUM
        else -> DistanceLevel.FAR
    }
}

/**
 * Projects a box's bottom centre onto the ground plane (§8): the ray through the contact pixel, its
 * angle β below the horizon from gravity, and `d = h / tan β` for phone height `h`. Then checks
 * whether that contact can be trusted.
 *
 * Single-threaded, like the extractor that calls it.
 */
class GroundContactDistanceEstimator(semantics: NavigationSemantics) {

    private val isGround = BooleanArray(semantics.classCount.coerceAtLeast(1)) {
        semantics.isPassable(it) || semantics.toGroundType(it) != GroundType.UNKNOWN
    }

    fun estimate(box: NormalizedRect, category: ObstacleCategory, frame: DistanceFrame): GroundContactEstimate {
        val config = frame.config
        val geometry = frame.geometry?.takeIf(::usable) ?: return GroundContactEstimate(ContactValidity.NO_GEOMETRY)
        val depression = depressionRad(box.centerX, box.maxY, geometry)
            ?: return GroundContactEstimate(ContactValidity.NO_GEOMETRY)
        val minDepression = Math.toRadians(config.minDepressionDeg.toDouble())
        if (depression < minDepression) return GroundContactEstimate(ContactValidity.NEAR_HORIZON)

        val h = geometry.phone.heightMeters.toDouble()
        val tolerance = when (geometry.phone.source) {
            HeightSource.CALIBRATED -> config.calibratedHeightTolerance
            HeightSource.DEFAULT_SEED, HeightSource.PRESET -> config.presetHeightTolerance
        }.toDouble()
        val delta = Math.toRadians(config.gravityToleranceDeg.toDouble())
        val point = h / tan(depression)
        val lower = h * (1 - tolerance) / tan(min(depression + delta, MAX_DEPRESSION))
        val upper = (depression - delta).takeIf { it > 0 }?.let { h * (1 + tolerance) / tan(it) }

        fun result(validity: ContactValidity) =
            GroundContactEstimate(validity, lower.toFloat(), point.toFloat(), upper?.toFloat())

        if (box.maxY >= 1f - config.clippedBottomMargin) return result(ContactValidity.CLIPPED)
        if (isOccluded(box, frame.otherBoxes, config)) return result(ContactValidity.OCCLUDED)
        val segmentation = frame.segmentation
        if (segmentation != null && !groundBelow(box, segmentation, config)) return result(ContactValidity.GROUND_UNSEEN)
        if (!plausible(box, category, depression, h, tolerance, geometry, config)) return result(ContactValidity.IMPLAUSIBLE_BOX)
        return result(ContactValidity.VALID)
    }

    /**
     * Angle below the horizon, radians, of the ray through the normalised image point ([x], [y]);
     * negative above the horizon. Null without intrinsics or gravity.
     */
    fun depressionRad(x: Float, y: Float, geometry: CameraGeometry): Double? {
        val k = geometry.intrinsics ?: return null
        val g = geometry.gravityInCamera ?: return null
        val rx = (x * k.width - k.cx).toDouble() / k.fx
        val ry = (y * k.height - k.cy).toDouble() / k.fy
        val norm = sqrt(rx * rx + ry * ry + 1.0)
        val q = g.x * rx + g.y * ry + g.z
        return asin((q / norm).coerceIn(-1.0, 1.0))
    }

    /**
     * Stale gravity is refused: the phone may have turned since. Estimated intrinsics and
     * receipt-time alignment are accepted -- they bend the angle a little, and the fusion can
     * only raise risk.
     */
    private fun usable(geometry: CameraGeometry): Boolean = when (val quality = geometry.quality) {
        GeometryQuality.Ok -> true
        is GeometryQuality.Degraded -> GeometryIssue.STALE_GRAVITY !in quality.issues
        is GeometryQuality.Unavailable -> false
    }

    /** A nearer box (lower bottom) covers the contact row across the box's central half. */
    private fun isOccluded(box: NormalizedRect, others: List<NormalizedRect>, config: GroundContactDistanceConfig): Boolean {
        val left = box.centerX - box.width / 4
        val right = box.centerX + box.width / 4
        return others.any { other ->
            other != box &&
                other.maxY > box.maxY + config.occluderNearerMargin &&
                other.y < box.maxY &&
                min(other.maxX, right) > max(other.x, left)
        }
    }

    /** The strip just below the contact, across the box's central half, shows enough ground. */
    private fun groundBelow(box: NormalizedRect, map: SegmentationMask, config: GroundContactDistanceConfig): Boolean {
        val top = floor(box.maxY * map.height).toInt().coerceIn(0, map.height)
        val bottom = min(map.height, top + max(1, (config.groundProbeHeight * map.height).toInt()))
        val left = floor((box.centerX - box.width / 4) * map.width).toInt().coerceIn(0, map.width - 1)
        val right = floor((box.centerX + box.width / 4) * map.width).toInt().coerceIn(left, map.width - 1)
        var samples = 0
        var ground = 0
        for (y in top until bottom) {
            val row = y * map.width
            var x = left
            while (x <= right) {
                val id = map.classMap[row + x]
                if (id in isGround.indices && isGround[id]) ground++
                samples++
                x += PROBE_STRIDE
            }
        }
        return samples > 0 && ground >= config.minGroundBelowRatio * samples
    }

    /**
     * The height the box implies for an upright object standing at the contact:
     * `H = h − d·tan β_top`, with the top ray's angle β_top. A box cut short at the bottom (legs
     * behind a wall) reads farther and *taller*; one that runs past the contact reads nearer and
     * shorter. A box whose top is clipped is not judged, and classes without a known size are not.
     */
    private fun plausible(
        box: NormalizedRect,
        category: ObstacleCategory,
        depression: Double,
        h: Double,
        tolerance: Double,
        geometry: CameraGeometry,
        config: GroundContactDistanceConfig,
    ): Boolean {
        val range = PLAUSIBLE_HEIGHT_M[category] ?: return true
        if (box.y <= config.clippedBottomMargin) return true
        val top = depressionRad(box.centerX, box.y, geometry) ?: return true
        val implied = h * (1 - tan(top) / tan(depression))
        val low = implied * (1 - tolerance)
        val high = implied * (1 + tolerance)
        return high >= range.start && low <= range.endInclusive
    }

    private companion object {
        const val PROBE_STRIDE = 2
        const val MAX_DEPRESSION = Math.PI / 2 - 1e-6

        /** Proposals, not calibrated: wide enough for children, seated people and buses. */
        val PLAUSIBLE_HEIGHT_M: Map<ObstacleCategory, ClosedFloatingPointRange<Double>> = mapOf(
            ObstacleCategory.PERSON to 0.7..2.4,
            ObstacleCategory.BICYCLE to 0.6..2.2,
            ObstacleCategory.VEHICLE to 0.9..4.5,
        )
    }
}
