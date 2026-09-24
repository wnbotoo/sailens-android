package com.sailens.shell.device

import android.content.Context
import com.sailens.shell.R

/** A state of Guidance itself, as opposed to something in the scene, that the user must hear. */
enum class GuidanceNotice {
    /** The session ended on an error. */
    FAILED,

    /** The session is still running but has stopped producing results. */
    STALLED,
}

/** Resolves [GuidanceNotice] to the words spoken for it. */
class GuidanceNoticeText(private val context: Context) {
    fun resolve(notice: GuidanceNotice): String = context.getString(
        when (notice) {
            GuidanceNotice.FAILED -> R.string.notice_guidance_failed
            GuidanceNotice.STALLED -> R.string.notice_guidance_stalled
        }
    )
}
