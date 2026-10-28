package com.sailens.guidance.evaluation

/** A wording as the speaker hears it: the message key, and whether it carries the NEAR prefix. */
internal typealias Wording = Pair<String, Boolean>

/**
 * Where the speaker model takes a sentence's duration from (det-first D1.4): **never from the capture
 * it is scored on**. Measuring a capture's own lifecycle and then predicting it gives the answer back
 * -- a wording spoken once is then predicted to the millisecond. The order is [measured] medians
 * (from other captures), then the D1.7 table plus [d17OffsetMs], then [SpeakerModel.DEFAULT_DURATION_MS].
 *
 * The offset: on the device a sentence runs from `started` to `done` some 45 ms longer than D1.7 timed
 * it alone, and a too-short duration leans toward "spoken to the end" -- the direction that hides a
 * regression. It is the median difference over the wordings both have, so it comes from the same
 * captures as [measured].
 */
internal class SpeakerDurations(private val measured: Map<Wording, Long>, private val measuredLabel: String) {

    val d17OffsetMs: Long = measured.mapNotNull { (w, ms) -> TtsDurations.zhCnRate130(w.first, w.second)?.let { ms - it } }
        .sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }

    /** Where [wording]'s duration comes from, and the duration in ms. */
    fun of(wording: Wording): Pair<String, Long> =
        measured[wording]?.let { measuredLabel to it }
            ?: TtsDurations.zhCnRate130(wording.first, wording.second)?.let { "d1.7 table" to it + d17OffsetMs }
            ?: ("default" to SpeakerModel.DEFAULT_DURATION_MS)

    val wordings: Map<Wording, Long> get() = measured

    companion object {
        /** The D1.7 table alone, no offset. */
        val D17_ONLY = SpeakerDurations(emptyMap(), "measured")

        /** Per wording, the median `started`-to-`done` of [samples] (the upper middle for an even count). */
        fun pooled(samples: List<Pair<Wording, Long>>, label: String) = SpeakerDurations(
            samples.groupBy({ it.first }, { it.second }).mapValues { (_, d) -> d.sorted()[d.size / 2] },
            label,
        )

        /**
         * The frozen table for the sealed set: the median over every calibration capture (C) of
         * D1.8, then the D1.7 table. Printed by `PromptPolicyBaselineTest` when run on C, which also
         * says whether it still matches.
         */
        val FROZEN_C = SpeakerDurations(CALIBRATION_C, "c table")
    }
}

private fun entry(key: String, near: Boolean, ms: Long) = (key to near) to ms

/** D1.8 calibration set C, 14 captures: median `started`-to-`done` per wording, in ms. */
private val CALIBRATION_C: Map<Wording, Long> = mapOf(
    entry("event_blocked", false, 1099),
    entry("event_ground_unrecognized", false, 3413),
    entry("event_obstacle_center", true, 1944),
    entry("event_obstacle_center_person", false, 1285),
    entry("event_obstacle_center_person", true, 1982),
    entry("event_obstacle_center_right_person", false, 1872),
    entry("event_obstacle_center_right_person", true, 2550),
    entry("event_obstacle_center_right_vehicle", true, 2498),
    entry("event_obstacle_center_vehicle", false, 1237),
    entry("event_obstacle_center_vehicle", true, 2000),
    entry("event_obstacle_left", true, 1916),
    entry("event_obstacle_left_center_person", false, 1883),
    entry("event_obstacle_left_center_person", true, 2501),
    entry("event_obstacle_left_center_vehicle", true, 2453),
    entry("event_obstacle_left_person", false, 1237),
    entry("event_obstacle_left_person", true, 1931),
    entry("event_obstacle_left_right_person", true, 2225),
    entry("event_obstacle_left_vehicle", false, 1253),
    entry("event_obstacle_left_vehicle", true, 1957),
    entry("event_obstacle_multiple_person", false, 1723),
    entry("event_obstacle_multiple_person", true, 2306),
    entry("event_obstacle_right", true, 1941),
    entry("event_obstacle_right_person", false, 1258),
    entry("event_obstacle_right_person", true, 1954),
    entry("event_obstacle_right_vehicle", false, 1251),
    entry("event_obstacle_right_vehicle", true, 1948),
    entry("event_path_complex", false, 1407),
    entry("event_traffic_light", false, 1259),
)
