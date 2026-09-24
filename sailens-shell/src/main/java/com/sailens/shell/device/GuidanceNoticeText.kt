package com.sailens.shell.device

import android.content.Context
import com.sailens.guidance.model.common.EventPriority
import com.sailens.shell.R

/**
 * A state of Guidance itself, as opposed to something in the scene, that the user must hear.
 *
 * @param speechPriority how the notice competes with navigation prompts once queued (on the
 *   [EventPriority] scale; only a strictly higher prompt may interrupt it).
 */
enum class GuidanceNotice(val speechPriority: Int) {
    /** The session ended on an error. Nothing is guiding any more, so nothing may cut this. */
    FAILED(Int.MAX_VALUE),

    /**
     * The session is still running but has stopped producing results. If results resume, a
     * CRITICAL prompt about what is in front of the user matters more than finishing this.
     */
    STALLED(EventPriority.HIGH.value),
    ;

    companion object {
        /** "Guidance interrupted": the app went to the background and is not guiding. */
        const val INTERRUPTED_PRIORITY: Int = Int.MAX_VALUE
    }
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
