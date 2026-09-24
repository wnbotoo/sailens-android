package com.sailens.shell.device

import androidx.annotation.StringRes
import com.sailens.shell.R

/**
 * Every Guidance message key mapped to the string resource that says it.
 *
 * Explicit on purpose. Looking the resource up by name at runtime (`Resources.getIdentifier`) is
 * invisible to the release build's resource shrinker, which then strips every one of these strings
 * and leaves the app reading raw keys aloud. A direct `R.string` reference keeps each string alive,
 * and `SceneEventStringsTest` proves this map covers exactly
 * [com.sailens.guidance.model.scene.SceneEventMessageKeys.all].
 */
internal object SceneEventStrings {
    val byKey: Map<String, Int> = mapOf(
        "event_blocked" to R.string.event_blocked,
        "event_camera_blocked" to R.string.event_camera_blocked,
        "event_ground_change" to R.string.event_ground_change,
        "event_ground_to_indoor" to R.string.event_ground_to_indoor,
        "event_ground_to_road" to R.string.event_ground_to_road,
        "event_ground_to_sidewalk" to R.string.event_ground_to_sidewalk,
        "event_ground_to_terrain" to R.string.event_ground_to_terrain,
        "event_intersection" to R.string.event_intersection,
        "event_low_light" to R.string.event_low_light,
        "event_narrowing" to R.string.event_narrowing,
        "event_obstacle_center" to R.string.event_obstacle_center,
        "event_obstacle_center_person" to R.string.event_obstacle_center_person,
        "event_obstacle_center_right" to R.string.event_obstacle_center_right,
        "event_obstacle_center_right_person" to R.string.event_obstacle_center_right_person,
        "event_obstacle_center_right_vehicle" to R.string.event_obstacle_center_right_vehicle,
        "event_obstacle_center_vehicle" to R.string.event_obstacle_center_vehicle,
        "event_obstacle_front_left" to R.string.event_obstacle_front_left,
        "event_obstacle_front_left_person" to R.string.event_obstacle_front_left_person,
        "event_obstacle_front_left_vehicle" to R.string.event_obstacle_front_left_vehicle,
        "event_obstacle_front_right" to R.string.event_obstacle_front_right,
        "event_obstacle_front_right_person" to R.string.event_obstacle_front_right_person,
        "event_obstacle_front_right_vehicle" to R.string.event_obstacle_front_right_vehicle,
        "event_obstacle_left" to R.string.event_obstacle_left,
        "event_obstacle_left_center" to R.string.event_obstacle_left_center,
        "event_obstacle_left_center_person" to R.string.event_obstacle_left_center_person,
        "event_obstacle_left_center_vehicle" to R.string.event_obstacle_left_center_vehicle,
        "event_obstacle_left_person" to R.string.event_obstacle_left_person,
        "event_obstacle_left_right" to R.string.event_obstacle_left_right,
        "event_obstacle_left_right_person" to R.string.event_obstacle_left_right_person,
        "event_obstacle_left_right_vehicle" to R.string.event_obstacle_left_right_vehicle,
        "event_obstacle_left_vehicle" to R.string.event_obstacle_left_vehicle,
        "event_obstacle_multiple" to R.string.event_obstacle_multiple,
        "event_obstacle_multiple_person" to R.string.event_obstacle_multiple_person,
        "event_obstacle_multiple_vehicle" to R.string.event_obstacle_multiple_vehicle,
        "event_obstacle_right" to R.string.event_obstacle_right,
        "event_obstacle_right_person" to R.string.event_obstacle_right_person,
        "event_obstacle_right_vehicle" to R.string.event_obstacle_right_vehicle,
        "event_path_complex" to R.string.event_path_complex,
        "event_road_warning" to R.string.event_road_warning,
        "event_road_warning_vehicle" to R.string.event_road_warning_vehicle,
        "event_traffic_light" to R.string.event_traffic_light,
    )

    @StringRes
    fun resourceFor(messageKey: String): Int? = byKey[messageKey]
}
