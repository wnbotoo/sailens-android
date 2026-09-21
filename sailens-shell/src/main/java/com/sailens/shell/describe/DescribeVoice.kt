package com.sailens.shell.describe

import com.sailens.output.SpeechEngineState
import com.sailens.output.SpeechManager
import com.sailens.output.SpeechOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Describe's own voice on the shared speech engine.
 *
 * Everything spoken here belongs to Describe, and [withdraw] takes back exactly that. There is no
 * way through this interface to stop anyone else's speech — which is the point: cancelling or
 * leaving a description must never be able to cut off a navigation warning that happens to be
 * playing at the same moment.
 */
interface DescribeVoice {
    /**
     * Waits, briefly, for the engine to be able to speak. Starting it is someone else's job; this
     * only avoids dropping a clause during the half second after the user turned speech on.
     *
     * @return false when the engine did not come up in time, or failed.
     */
    suspend fun awaitReady(): Boolean

    /** Queues [text] after whatever is already playing, as Describe's. */
    fun speak(text: String)

    /** Takes back everything Describe has queued or is speaking. Nothing else. */
    fun withdraw()
}

class SpeechManagerDescribeVoice(
    private val speechManager: SpeechManager,
    private val readyTimeoutMs: Long = READY_TIMEOUT_MS,
) : DescribeVoice {
    private val owner = SpeechOwner("describe")

    override suspend fun awaitReady(): Boolean {
        if (speechManager.isReady) return true
        withTimeoutOrNull(readyTimeoutMs) {
            speechManager.state.first {
                it == SpeechEngineState.READY || it == SpeechEngineState.UNAVAILABLE
            }
        }
        return speechManager.isReady
    }

    override fun speak(text: String) {
        speechManager.speakSystemNotice(text, owner)
    }

    override fun withdraw() {
        speechManager.withdraw(owner)
    }

    /** Whether anything Describe queued is still queued or speaking. For diagnostics and tests. */
    val hasQueuedSpeech: Boolean
        get() = speechManager.hasQueued(owner)

    private companion object {
        /** Longer than a normal engine start, shorter than the silence a blind user will wait out. */
        const val READY_TIMEOUT_MS = 3_000L
    }
}
