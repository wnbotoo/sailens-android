package com.sailens.shell.device

import android.content.Context
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.processor.decision.SpeechRanks
import com.sailens.guidance.processor.decision.SpeechScale
import com.sailens.shell.R

/**
 * A state of Guidance itself, as opposed to something in the scene, that the user must hear.
 *
 * [speechPriority] is how the notice competes with navigation prompts once queued (on [SpeechScale];
 * only a strictly higher prompt may interrupt it).
 */
enum class GuidanceNotice {
    /** The session ended on an error. Nothing is guiding any more, so nothing may cut this. */
    FAILED,

    /**
     * The session is still running but has stopped producing results. If results resume, a
     * CRITICAL prompt about what is in front of the user matters more than finishing this.
     */
    STALLED,
    ;

    fun speechPriority(detFirst: DetFirstConfig): Int = when (this) {
        FAILED -> Int.MAX_VALUE
        STALLED -> SpeechScale.of(EventPriority.HIGH, SpeechRanks.ofStalledNotice(detFirst))
    }

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
