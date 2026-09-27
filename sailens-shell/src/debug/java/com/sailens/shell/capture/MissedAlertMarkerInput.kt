package com.sailens.shell.capture

import android.content.Context
import android.view.KeyEvent
import com.sailens.guidance.trace.capture.MarkerSource
import com.sailens.output.HapticPlayer
import com.sailens.shell.app.HardwareKeyHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal enum class MarkerKeyDecision {
    /** Not ours: let the system handle it (normal volume control). */
    PASS,

    /** Ours but not a new press (repeat or key-up): swallow it so the volume does not change. */
    CONSUME,

    /** A fresh press while capturing: record a marker. */
    MARK,
}

/**
 * Volume Down marks a missed alert while -- and only while -- a capture is recording. One press is
 * one marker: key repeats from holding the key, and the key-up, are swallowed but not counted.
 */
internal fun markerKeyDecision(capturing: Boolean, keyCode: Int, action: Int, repeatCount: Int): MarkerKeyDecision {
    if (!capturing || keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return MarkerKeyDecision.PASS
    return if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) MarkerKeyDecision.MARK else MarkerKeyDecision.CONSUME
}

/**
 * Records a marker and confirms it with a short vibration -- only once the marker was actually
 * written, so the person recording can trust the buzz without looking.
 */
internal class MissedAlertMarkers(
    context: Context,
    private val controller: FieldCaptureController,
) {
    private val haptics = HapticPlayer(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun mark(source: MarkerSource) {
        scope.launch {
            if (controller.recordMarker(source)) haptics.play(CONFIRM, CONFIRM_AMPLITUDE)
        }
    }

    private companion object {
        val CONFIRM = longArrayOf(0, 60)
        const val CONFIRM_AMPLITUDE = 200
    }
}

internal class VolumeDownMarkerKeyHandler(
    private val controller: FieldCaptureController,
    private val markers: MissedAlertMarkers,
) : HardwareKeyHandler {
    override fun onKeyEvent(event: KeyEvent): Boolean =
        when (markerKeyDecision(controller.isCapturing.value, event.keyCode, event.action, event.repeatCount)) {
            MarkerKeyDecision.PASS -> false
            MarkerKeyDecision.CONSUME -> true
            MarkerKeyDecision.MARK -> {
                markers.mark(MarkerSource.VOLUME_DOWN)
                true
            }
        }
}
