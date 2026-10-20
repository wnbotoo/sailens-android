package com.sailens.shell.groundcontact

import androidx.lifecycle.ViewModel
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.Placement
import com.sailens.guidance.processor.perception.PerceptionProfileManager
import com.sailens.shell.guidance.settings.PhoneGeometryChoice
import com.sailens.shell.guidance.settings.PhoneGeometryStore
import kotlinx.coroutines.flow.StateFlow

/**
 * The debug "ground-contact distance" screen (M3a): how the phone is carried, and the experimental
 * switch. The switch is remembered by [store] and handed to [profiles], which applies it at the next
 * Guidance session start, so a running session (and its capture) never changes midway.
 */
internal class GroundContactViewModel(
    private val store: PhoneGeometryStore,
    private val profiles: PerceptionProfileManager,
) : ViewModel() {

    val choice: StateFlow<PhoneGeometryChoice> = store.choice

    fun setEnabled(enabled: Boolean) {
        store.setGroundContactEnabled(enabled)
        profiles.selectGroundContact(enabled)
    }

    fun setPlacement(placement: Placement?) = store.setPlacement(placement)

    /**
     * Saves a body height typed in centimetres; blank clears it.
     * @return false, saving nothing, when [text] is not a height in the accepted range.
     */
    fun saveBodyHeight(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            store.setBodyHeightCm(null)
            return true
        }
        val cm = trimmed.toIntOrNull()?.takeIf { it in PhoneGeometrySettings.BODY_HEIGHT_CM_RANGE } ?: return false
        store.setBodyHeightCm(cm)
        return true
    }
}
