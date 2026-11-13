package com.sailens.shell.safetynotice

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which version of the safety notice the person has confirmed, kept across launches. */
interface SafetyNoticeStore {
    /** The version last confirmed, or null if the notice has never been confirmed. */
    fun confirmedVersion(): Int?

    fun recordConfirmed(version: Int)
}

/**
 * [SafetyNoticeStore] in the app's private preferences. The write is asynchronous; if the process
 * dies before it lands, the confirmation is lost and the notice is shown again, never the reverse.
 */
class SharedPreferencesSafetyNoticeStore(context: Context) : SafetyNoticeStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun confirmedVersion(): Int? =
        if (preferences.contains(KEY_CONFIRMED_VERSION)) preferences.getInt(KEY_CONFIRMED_VERSION, 0) else null

    override fun recordConfirmed(version: Int) {
        preferences.edit { putInt(KEY_CONFIRMED_VERSION, version) }
    }

    private companion object {
        const val PREFERENCES_NAME = "safety_notice"
        const val KEY_CONFIRMED_VERSION = "confirmed_version"
    }
}

/**
 * The safety notice in front of a start of Guidance, until the person has confirmed its current
 * version.
 *
 * Only the notice's own button confirms it ([confirm]): that records [version], and the start the
 * notice was shown for goes ahead. Leaving the notice any other way ([dismiss]: Back, a tap outside
 * it) records nothing and starts nothing, so the next start shows it again. Guidance is not running
 * while the notice is shown: it is shown instead of a start.
 *
 * Whether it is showing lives in the guidance screen's view model, so it survives a rotation. It does
 * not survive the process being killed, and need not: Guidance does not restart by itself after that
 * either, and the notice comes back the next time the person presses start.
 */
internal class SafetyNoticeGate(
    private val store: SafetyNoticeStore,
    private val version: Int = SAFETY_NOTICE_VERSION,
) {
    private val _isShown = MutableStateFlow(false)
    val isShown: StateFlow<Boolean> = _isShown.asStateFlow()

    /** This exact version was confirmed. An earlier one does not count, and neither does a later one. */
    val isConfirmed: Boolean get() = store.confirmedVersion() == version

    fun show() {
        _isShown.value = true
    }

    /**
     * The notice's button: records the confirmation and closes the notice. Returns whether the start
     * should now go ahead, which is only when the notice was showing: a second press arriving after
     * the first closed it does nothing.
     */
    fun confirm(): Boolean {
        if (!_isShown.value) return false
        store.recordConfirmed(version)
        _isShown.value = false
        return true
    }

    /** Back, or a tap outside the notice: it closes, nothing is recorded, and nothing starts. */
    fun dismiss() {
        _isShown.value = false
    }
}
