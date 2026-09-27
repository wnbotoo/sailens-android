package com.sailens.shell.capture

import android.content.Context
import com.sailens.shell.R
import com.sailens.shell.app.GuidanceStartGate

/**
 * Holds Guidance off while a capture ZIP is being exported, so an export never shares the storage
 * with a recording in progress (the controller also ends such a capture at once, as a backstop).
 */
internal class FieldCaptureStartGate(
    context: Context,
    private val controller: FieldCaptureController,
) : GuidanceStartGate {
    private val appContext = context.applicationContext

    override fun startBlockedReason(): String? =
        if (controller.isManagingFiles.value) appContext.getString(R.string.field_capture_export_blocks_guidance) else null
}
