package com.sailens.output

/**
 * Whoever queued an utterance, so that party can later take back what it queued — and nothing
 * anyone else queued.
 *
 * Deliberately opaque. This module compares owners by identity and never interprets them: which
 * product feature an owner stands for, and whose speech outranks whose, is decided above it
 * (architecture.md §6.6). Two owners created with the same [name] are still two owners.
 */
public class SpeechOwner(
    /** Diagnostic label. Appears in logs only. */
    public val name: String,
) {
    override fun toString(): String = "SpeechOwner($name)"
}

/**
 * What has been handed to the TTS engine and has not finished yet, in order, and who asked for it.
 *
 * Android's TextToSpeech can only flush its whole queue; it cannot drop one utterance out of the
 * middle. Taking back one owner's speech therefore means flushing everything and handing the rest
 * back. This is the bookkeeping that makes "the rest" knowable.
 *
 * Pure and synchronized, so the rules can be tested on the JVM and queried from any thread.
 */
internal class UtteranceLedger {

    internal data class Entry(
        val id: String,
        val text: String,
        /** Null for speech nobody claimed, which no owner can withdraw. */
        val owner: SpeechOwner?,
        /** Wall-clock deadline, or null for speech that stays valid until it is spoken. */
        val expiresAtMs: Long?,
    ) {
        fun isAliveAt(nowMs: Long): Boolean = expiresAtMs == null || nowMs <= expiresAtMs
    }

    private val entries = LinkedHashMap<String, Entry>()

    /** The engine was told to drop everything before this utterance (QUEUE_FLUSH). */
    @Synchronized
    fun flushAndAdd(entry: Entry) {
        entries.clear()
        entries[entry.id] = entry
    }

    /** Appended after whatever is already queued (QUEUE_ADD). */
    @Synchronized
    fun add(entry: Entry) {
        entries[entry.id] = entry
    }

    /**
     * A done, stop or error callback. Unknown ids are ignored on purpose: utterances removed by a
     * flush still report back afterwards, and must not disturb what replaced them.
     */
    @Synchronized
    fun finished(id: String?) {
        if (id != null) entries.remove(id)
    }

    /** The engine's queue was emptied without anything being handed back. */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun has(owner: SpeechOwner): Boolean = entries.values.any { it.owner === owner }

    @Synchronized
    fun size(): Int = entries.size

    /**
     * Forgets everything and returns, in their original order, the utterances that must be handed
     * back to the engine after it is flushed: everyone else's speech that is still valid at [nowMs].
     * [owner]'s own utterances are the ones left out.
     */
    @Synchronized
    fun withdraw(owner: SpeechOwner, nowMs: Long): List<Entry> {
        val survivors = entries.values.filter { it.owner !== owner && it.isAliveAt(nowMs) }
        entries.clear()
        return survivors
    }
}
