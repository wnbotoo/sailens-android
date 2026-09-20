package com.sailens.shell.device

import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.output.Announcement

/**
 * Turns a Guidance event into something the output layer can say.
 *
 * This is the seam §6.6 draws: Guidance emits typed events with an urgency, sailens-output knows
 * only text and a priority number, and the mapping between them is product policy that belongs
 * here in the shell. It is also where the copy is resolved, because the wording is Guidance's,
 * not the speech engine's.
 */
class GuidanceAnnouncements(
    private val textResolver: SceneEventTextResolver,
) {
    fun announcementFor(event: SceneEvent): Announcement = Announcement(
        id = event.id.toString(),
        key = event.messageKey,
        text = textResolver.resolve(event.toSceneEventText()),
        priority = event.priority.value,
        expiresAtMs = event.expiresAt,
    )
}
