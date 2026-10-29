package com.sailens.guidance.geometry

import kotlin.math.roundToInt

/** Where the phone height came from. */
enum class HeightSource {
    /** The runtime profile's seed; nobody has said anything about this user. */
    DEFAULT_SEED,

    /** A typical height for the [PhoneGeometrySettings.placement] the user chose. */
    PRESET,

    /** Measured for this user. */
    CALIBRATED,
}

/**
 * How the phone is carried (docs/geometry.md §4). Each placement has a typical camera height for an
 * adult and a ratio of body height, for users who give theirs. Both are proposals to be checked
 * against captures, not calibrated values.
 */
enum class Placement(val presetHeightMeters: Float, val bodyHeightRatio: Float) {
    CHEST_LANYARD(1.25f, 0.73f),
    HANDHELD_CHEST(1.20f, 0.70f),
    WAIST(1.00f, 0.58f),
}

/**
 * How the user carries the phone. Owned by a shell store and injected into Guidance; the
 * runtime profile supplies only the default. Read by the ground-contact distance.
 */
data class PhoneGeometrySettings(
    val heightMeters: Float,
    val source: HeightSource,
    val placement: Placement? = null,
) {
    companion object {
        /** The seed: chest height. */
        val DEFAULT = PhoneGeometrySettings(heightMeters = 1.3f, source = HeightSource.DEFAULT_SEED)

        /** Body heights taken as real; anything else is treated as not given. */
        val BODY_HEIGHT_CM_RANGE: IntRange = 100..220

        /**
         * The settings for what the user said:
         * - nothing gives the seed;
         * - a placement gives its preset height;
         * - a placement and a body height in [BODY_HEIGHT_CM_RANGE] give the placement's ratio of
         *   it, rounded to the centimetre.
         *
         * A body height without a placement says nothing about where the phone is, so it gives the
         * seed too.
         */
        fun of(placement: Placement?, bodyHeightCm: Int?): PhoneGeometrySettings {
            placement ?: return DEFAULT
            val body = bodyHeightCm?.takeIf { it in BODY_HEIGHT_CM_RANGE }
                ?: return PhoneGeometrySettings(placement.presetHeightMeters, HeightSource.PRESET, placement)
            val heightCm = (body * placement.bodyHeightRatio).roundToInt()
            return PhoneGeometrySettings(heightCm / 100f, HeightSource.CALIBRATED, placement)
        }
    }
}
