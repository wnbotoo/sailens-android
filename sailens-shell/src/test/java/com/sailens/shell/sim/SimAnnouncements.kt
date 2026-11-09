package com.sailens.shell.sim

import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.decision.SpeechScale
import com.sailens.output.Announcement
import com.sailens.shell.device.SceneEventText
import com.sailens.shell.device.toSceneEventText

/**
 * `GuidanceAnnouncements.announcementFor` (`GuidanceAnnouncements.kt:18-24`), mirrored: the id, the key, the real
 * [SpeechScale.of] priority and the expiry as there. Only the words differ: `SceneEventTextResolver` reads string
 * resources through a `Context`, so the words are [text], a rendering that changes whenever the inputs to the real
 * wording do. Kept in step with the file by `SimulatorMirrorPinTest`.
 */
internal object SimAnnouncements {
    fun announcementFor(event: SceneEvent): Announcement = Announcement(
        id = event.id.toString(),
        key = event.messageKey,
        text = text(event.toSceneEventText()),
        priority = SpeechScale.of(event),
        expiresAtMs = event.expiresAt,
    )

    /**
     * What `SceneEventTextResolver.resolve` is given, written out: the key, its parameters in the order the resolver
     * passes them (sorted by name), the direction suffix and the "near" prefix, which it wraps in that order.
     */
    fun text(event: SceneEventText): String = buildString {
        append(event.messageKey)
        if (event.messageParams.isNotEmpty()) {
            append(event.messageParams.toSortedMap().entries.joinToString(";", prefix = "{", postfix = "}") { "${it.key}=${it.value}" })
        }
        event.directionHint?.let { append("+bias_").append(it.name.lowercase()) }
        if (event.isNear) append("+near")
    }
}

/** The screen reader, as a recorder of each line it is given. It reports no completion, so it is told nothing back. */
internal class SimScreenReader(private val record: (text: String) -> Unit) {
    fun announce(text: String) = record(text)
}

/**
 * The scene description, as a double: a held floor the script begins for a length of time. [holdsTheFloor] reads
 * the clock; [preemptForGuidance] ends a held floor at once and says so. Nothing is said for it.
 */
internal class SimDescription(
    private val now: () -> Long,
    private val record: (what: String) -> Unit,
) : DescriptionDevice {
    private var holdsUntilMs: Long? = null

    override val holdsTheFloor: Boolean get() = holdsUntilMs?.let { now() < it } == true

    /** A description begins holding the floor for [durationMs]. */
    fun begin(durationMs: Long) {
        holdsUntilMs = now() + durationMs
        record("holds")
    }

    /** The time a description began for has passed: it ends, unless Guidance preempted it first. */
    fun endIfHeld(untilMs: Long) {
        if (holdsUntilMs != untilMs) return
        holdsUntilMs = null
        record("ends")
    }

    override fun preemptForGuidance(reason: String) {
        if (!holdsTheFloor) return
        holdsUntilMs = null
        record("preempted($reason)")
    }
}
