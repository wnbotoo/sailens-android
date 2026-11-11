package com.sailens.shell.groundcontact

import androidx.lifecycle.ViewModel
import com.sailens.guidance.depth.geometry.DepthGeometrySwitch
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.Placement
import com.sailens.guidance.processor.perception.PerceptionProfileManager
import com.sailens.shell.guidance.settings.PhoneGeometryChoice
import com.sailens.shell.guidance.settings.PhoneGeometryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The debug "ground-contact distance" screen: how the phone is carried, and the experimental
 * switch. The switch is remembered by [store] and handed to [profiles], which applies it at the next
 * Guidance session start, so a running session (and its capture) never changes midway.
 *
 * The same screen switches the depth lane ([depthSwitch], docs/geometry.md §6), read at the next
 * Guidance session start too. It is not remembered: every app start begins with it off.
 */
internal class GroundContactViewModel(
    private val store: PhoneGeometryStore,
    private val profiles: PerceptionProfileManager,
    private val depthSwitch: DepthGeometrySwitch,
) : ViewModel() {

    val choice: StateFlow<PhoneGeometryChoice> = store.choice

    private val _depthLaneEnabled = MutableStateFlow(depthSwitch.selected)
    val depthLaneEnabled: StateFlow<Boolean> = _depthLaneEnabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        store.setGroundContactEnabled(enabled)
        profiles.selectGroundContact(enabled)
    }

    fun setDepthLaneEnabled(enabled: Boolean) {
        depthSwitch.select(enabled)
        _depthLaneEnabled.value = enabled
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
