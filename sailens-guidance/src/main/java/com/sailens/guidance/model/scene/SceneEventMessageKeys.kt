package com.sailens.guidance.model.scene

import com.sailens.guidance.model.common.DirectionZone

/**
 * Every [SceneEvent.messageKey] Guidance can emit, in one place.
 *
 * The keys are Guidance's vocabulary; the words are the shell's (string resources). The two meet
 * at a key, and a key the shell cannot resolve is spoken as the raw key -- "event_obstacle_center"
 * read aloud to someone walking. So the set is closed and enumerable on purpose: the shell maps
 * each key to a resource at compile time and a test proves the map covers [all]. Looking keys up by
 * name at runtime instead (`Resources.getIdentifier`) is invisible to resource shrinking, which is
 * how the release build once lost every announcement.
 */
object SceneEventMessageKeys {
    const val CAMERA_BLOCKED = "event_camera_blocked"
    const val LOW_LIGHT = "event_low_light"
    const val BLOCKED = "event_blocked"
    const val PATH_COMPLEX = "event_path_complex"
    const val NARROWING = "event_narrowing"
    const val INTERSECTION = "event_intersection"
    const val TRAFFIC_LIGHT = "event_traffic_light"
    const val ROAD_WARNING = "event_road_warning"
    const val ROAD_WARNING_VEHICLE = "event_road_warning_vehicle"
    const val GROUND_TO_ROAD = "event_ground_to_road"
    const val GROUND_TO_TERRAIN = "event_ground_to_terrain"
    const val GROUND_TO_SIDEWALK = "event_ground_to_sidewalk"
    const val GROUND_TO_INDOOR = "event_ground_to_indoor"
    const val GROUND_CHANGE = "event_ground_change"
    const val GROUND_UNRECOGNIZED = "event_ground_unrecognized"

    private const val OBSTACLE_PREFIX = "event_obstacle_"

    /** Category suffixes an obstacle key may carry; null is "no category" (static/unknown). */
    const val SUFFIX_PERSON = "person"
    const val SUFFIX_VEHICLE = "vehicle"
    private val OBSTACLE_SUFFIXES: List<String?> = listOf(null, SUFFIX_PERSON, SUFFIX_VEHICLE)

    /** The zone part of a merged key that spans more than one zone. */
    const val ZONES_LEFT_CENTER = "left_center"
    const val ZONES_CENTER_RIGHT = "center_right"
    const val ZONES_LEFT_RIGHT = "left_right"
    const val ZONES_MULTIPLE = "multiple"
    private val MERGED_ZONE_PARTS =
        listOf(ZONES_LEFT_CENTER, ZONES_CENTER_RIGHT, ZONES_LEFT_RIGHT, ZONES_MULTIPLE)

    /** The zone part for a single zone, e.g. `front_left`. */
    fun zonePart(zone: DirectionZone): String = zone.name.lowercase()

    /** `event_obstacle_<zonePart>[_<suffix>]`. */
    fun obstacle(zonePart: String, suffix: String?): String {
        val zoneKey = "$OBSTACLE_PREFIX$zonePart"
        return if (suffix == null) zoneKey else "${zoneKey}_$suffix"
    }

    /** The category suffix of an obstacle key, or null when it carries none. */
    fun obstacleSuffix(messageKey: String): String? = when {
        messageKey.endsWith("_$SUFFIX_PERSON") -> SUFFIX_PERSON
        messageKey.endsWith("_$SUFFIX_VEHICLE") -> SUFFIX_VEHICLE
        else -> null
    }

    val all: Set<String> = buildSet {
        addAll(
            listOf(
                CAMERA_BLOCKED, LOW_LIGHT, BLOCKED, PATH_COMPLEX, NARROWING, INTERSECTION,
                TRAFFIC_LIGHT, ROAD_WARNING, ROAD_WARNING_VEHICLE, GROUND_TO_ROAD,
                GROUND_TO_TERRAIN, GROUND_TO_SIDEWALK, GROUND_TO_INDOOR, GROUND_CHANGE,
                GROUND_UNRECOGNIZED,
            )
        )
        val zoneParts = DirectionZone.entries.map(::zonePart) + MERGED_ZONE_PARTS
        for (zonePart in zoneParts) {
            for (suffix in OBSTACLE_SUFFIXES) add(obstacle(zonePart, suffix))
        }
    }
}
