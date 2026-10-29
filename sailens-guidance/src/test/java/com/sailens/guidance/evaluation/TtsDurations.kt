package com.sailens.guidance.evaluation

/**
 * Measured sentence durations from the D1.7 table (`docs/experiments/detfirst-tts-durations-2026-10.md`):
 * zh-CN, `com.google.android.tts` at speech rate 1.30 on the recording phone -- the D1.8 settings.
 * Each value is the median of three plays, `onStart` to `onDone`. The speaker model falls back to it
 * for a wording the capture never spoke to the end; it does not hold for another language, engine or rate.
 */
internal object TtsDurations {

    /** Median duration in ms of [messageKey] (with the NEAR prefix when [near]), or null if not measured. */
    fun zhCnRate130(messageKey: String, near: Boolean): Long? = ZH_CN_RATE_130[messageKey to near]

    private fun entry(key: String, near: Boolean, ms: Long) = (key to near) to ms

    private val ZH_CN_RATE_130: Map<Pair<String, Boolean>, Long> = mapOf(
        entry("event_camera_blocked", false, 1176),
        entry("event_low_light", false, 1055),
        entry("event_blocked", false, 1054),
        entry("event_path_complex", false, 1395),
        entry("event_narrowing", false, 1056),
        entry("event_intersection", false, 1039),
        entry("event_traffic_light", false, 1215),
        entry("event_road_warning", false, 1020),
        entry("event_road_warning_vehicle", false, 1074),
        entry("event_ground_to_road", false, 1035),
        entry("event_ground_to_terrain", false, 1015),
        entry("event_ground_to_sidewalk", false, 1015),
        entry("event_ground_to_indoor", false, 1014),
        entry("event_ground_change", false, 1014),
        entry("event_ground_unrecognized", false, 3414),
        entry("event_obstacle_left", false, 1140),
        entry("event_obstacle_left", true, 1864),
        entry("event_obstacle_left_person", false, 1176),
        entry("event_obstacle_left_person", true, 1919),
        entry("event_obstacle_left_vehicle", false, 1194),
        entry("event_obstacle_left_vehicle", true, 1898),
        entry("event_obstacle_center", false, 1154),
        entry("event_obstacle_center", true, 1878),
        entry("event_obstacle_center_person", false, 1232),
        entry("event_obstacle_center_person", true, 1970),
        entry("event_obstacle_center_vehicle", false, 1193),
        entry("event_obstacle_center_vehicle", true, 1952),
        entry("event_obstacle_right", false, 1134),
        entry("event_obstacle_right", true, 1893),
        entry("event_obstacle_right_person", false, 1212),
        entry("event_obstacle_right_person", true, 1955),
        entry("event_obstacle_right_vehicle", false, 1193),
        entry("event_obstacle_right_vehicle", true, 1938),
        entry("event_obstacle_front_left", false, 1275),
        entry("event_obstacle_front_left", true, 2074),
        entry("event_obstacle_front_left_person", false, 1513),
        entry("event_obstacle_front_left_person", true, 2133),
        entry("event_obstacle_front_left_vehicle", false, 1335),
        entry("event_obstacle_front_left_vehicle", true, 2096),
        entry("event_obstacle_front_right", false, 1274),
        entry("event_obstacle_front_right", true, 2071),
        entry("event_obstacle_front_right_person", false, 1495),
        entry("event_obstacle_front_right_person", true, 2113),
        entry("event_obstacle_front_right_vehicle", false, 1316),
        entry("event_obstacle_front_right_vehicle", true, 2095),
        entry("event_obstacle_left_center", false, 1814),
        entry("event_obstacle_left_center", true, 2424),
        entry("event_obstacle_left_center_person", false, 1877),
        entry("event_obstacle_left_center_person", true, 2495),
        entry("event_obstacle_left_center_vehicle", false, 1875),
        entry("event_obstacle_left_center_vehicle", true, 2453),
        entry("event_obstacle_center_right", false, 1817),
        entry("event_obstacle_center_right", true, 2394),
        entry("event_obstacle_center_right_person", false, 1872),
        entry("event_obstacle_center_right_person", true, 2493),
        entry("event_obstacle_center_right_vehicle", false, 1872),
        entry("event_obstacle_center_right_vehicle", true, 2459),
        entry("event_obstacle_left_right", false, 1775),
        entry("event_obstacle_left_right", true, 2311),
        entry("event_obstacle_left_right_person", false, 1676),
        entry("event_obstacle_left_right_person", true, 2235),
        entry("event_obstacle_left_right_vehicle", false, 1640),
        entry("event_obstacle_left_right_vehicle", true, 2168),
        entry("event_obstacle_multiple", false, 1573),
        entry("event_obstacle_multiple", true, 2153),
        entry("event_obstacle_multiple_person", false, 1675),
        entry("event_obstacle_multiple_person", true, 2253),
        entry("event_obstacle_multiple_vehicle", false, 1615),
        entry("event_obstacle_multiple_vehicle", true, 2195),
        entry("notice_failed", false, 4374),
        entry("notice_stalled", false, 3473),
    )
}
