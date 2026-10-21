package com.sailens.shell.guidance.settings

import android.content.Context
import android.content.SharedPreferences
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.Placement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the user said about carrying the phone (M3a), and whether the ground-contact distance is
 * switched on.
 *
 * @param bodyHeightCm optional; only used together with a [placement].
 * @param groundContactEnabled the experimental switch. It reaches Guidance through
 *   `PerceptionProfileManager.selectGroundContact`, at the next session start.
 */
data class PhoneGeometryChoice(
    val placement: Placement? = null,
    val bodyHeightCm: Int? = null,
    val groundContactEnabled: Boolean = false,
) {
    /** The settings Guidance reads: the seed until a placement is chosen. */
    val settings: PhoneGeometrySettings get() = PhoneGeometrySettings.of(placement, bodyHeightCm)
}

/**
 * Persists [PhoneGeometryChoice]. Guidance reads [current] once per frame, so it is a plain value
 * read; the only writer is the debug "ground-contact distance" screen until M3a's enable gate is met.
 */
class PhoneGeometryStore internal constructor(private val persistence: Persistence) {

    constructor(context: Context) : this(
        SharedPreferencesPersistence(context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)),
    )

    /** Where the choice is kept; a seam so the store can be tested without Android. */
    internal interface Persistence {
        fun read(): PhoneGeometryChoice
        fun write(choice: PhoneGeometryChoice)
    }

    private val _choice = MutableStateFlow(persistence.read())
    val choice: StateFlow<PhoneGeometryChoice> = _choice.asStateFlow()

    /** The phone settings for the frame being processed. */
    fun current(): PhoneGeometrySettings = _choice.value.settings

    fun setPlacement(placement: Placement?) = change { it.copy(placement = placement) }

    /** Null clears it; a value outside the plausible range is kept but reads as not given. */
    fun setBodyHeightCm(cm: Int?) = change { it.copy(bodyHeightCm = cm) }

    fun setGroundContactEnabled(enabled: Boolean) = change { it.copy(groundContactEnabled = enabled) }

    private fun change(transform: (PhoneGeometryChoice) -> PhoneGeometryChoice) {
        _choice.update(transform)
        persistence.write(_choice.value)
    }

    private class SharedPreferencesPersistence(private val preferences: SharedPreferences) : Persistence {
        override fun read() = PhoneGeometryChoice(
            placement = preferences.getString(KEY_PLACEMENT, null)?.let { stored -> Placement.entries.find { it.name == stored } },
            bodyHeightCm = if (preferences.contains(KEY_BODY_HEIGHT_CM)) preferences.getInt(KEY_BODY_HEIGHT_CM, 0) else null,
            groundContactEnabled = preferences.getBoolean(KEY_GROUND_CONTACT_ENABLED, false),
        )

        override fun write(choice: PhoneGeometryChoice) {
            preferences.edit().apply {
                if (choice.placement == null) remove(KEY_PLACEMENT) else putString(KEY_PLACEMENT, choice.placement.name)
                if (choice.bodyHeightCm == null) remove(KEY_BODY_HEIGHT_CM) else putInt(KEY_BODY_HEIGHT_CM, choice.bodyHeightCm)
                putBoolean(KEY_GROUND_CONTACT_ENABLED, choice.groundContactEnabled)
            }.apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_geometry_settings"
        const val KEY_PLACEMENT = "placement"
        const val KEY_BODY_HEIGHT_CM = "body_height_cm"
        const val KEY_GROUND_CONTACT_ENABLED = "ground_contact_enabled"
    }
}
