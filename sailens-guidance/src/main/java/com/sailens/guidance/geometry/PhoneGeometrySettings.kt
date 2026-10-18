package com.sailens.guidance.geometry

/** Where the phone height came from. */
enum class HeightSource {
    /** The runtime profile's seed; nobody has said anything about this user. */
    DEFAULT_SEED,

    /** A typical height for the [PhoneGeometrySettings.placement] the user chose. */
    PRESET,

    /** Measured for this user. */
    CALIBRATED,
}

enum class Placement { CHEST_LANYARD, HANDHELD_CHEST, WAIST }

/**
 * How the user carries the phone (§3). Owned by a shell store and injected into Guidance; the
 * runtime profile supplies only the default. Nothing reads the height until M3a.
 */
data class PhoneGeometrySettings(
    val heightMeters: Float,
    val source: HeightSource,
    val placement: Placement? = null,
) {
    companion object {
        /** The seed the roadmap settled on: chest height. */
        val DEFAULT = PhoneGeometrySettings(heightMeters = 1.3f, source = HeightSource.DEFAULT_SEED)
    }
}
