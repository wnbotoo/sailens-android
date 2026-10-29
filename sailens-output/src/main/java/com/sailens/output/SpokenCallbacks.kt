package com.sailens.output

/**
 * The callers waiting to hear that an announcement was heard out ([SpeechManager.speak]'s `onSpoken`),
 * by the announcement's id.
 *
 * Heard out is the engine's `onDone` for any submission of the announcement: the audible output has
 * finished. A hand-back after [SpeechManager.withdraw] submits the same words under the original id plus
 * [requeueSuffix], so the original id is what is matched. An `onDone` that arrives after a flush still
 * counts; nothing else (stop, error, a flush without `onDone`) is reported, because the caller's question
 * is only "was it heard?" and the answer to everything else is "not yet".
 *
 * Bounded: an announcement that never finishes is forgotten once [capacity] newer ones are waiting.
 * Main thread only.
 */
internal class SpokenCallbacks(
    private val requeueSuffix: String,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val waiting = LinkedHashMap<String, () -> Unit>()

    fun register(announcementId: String, onSpoken: () -> Unit) {
        waiting.remove(announcementId)
        waiting[announcementId] = onSpoken
        while (waiting.size > capacity) waiting.remove(waiting.keys.first())
    }

    /** The engine reported `onDone` for the submission [attemptId]. Calls its caller once. */
    fun done(attemptId: String?) {
        val id = attemptId ?: return
        val onSpoken = waiting.remove(id.substringBefore(requeueSuffix)) ?: return
        try {
            onSpoken()
        } catch (e: RuntimeException) {
            // A caller's bookkeeping must never reach the speech path.
        }
    }

    fun clear() = waiting.clear()

    fun size(): Int = waiting.size

    companion object {
        const val DEFAULT_CAPACITY: Int = 16
    }
}
