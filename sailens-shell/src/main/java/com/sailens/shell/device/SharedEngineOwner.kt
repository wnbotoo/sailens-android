package com.sailens.shell.device

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the lifetime of the engines every screen shares — the speech engine and the
 * scene-description model — so that no single screen does.
 *
 * Each engine is one instance for the whole app, and more than one screen uses it. When screens
 * released them on their way out, closing the Describe screen released the speech engine Guidance
 * was still speaking through: Guidance saw its engine drop back to idle, with nothing to start it
 * again, in the middle of a walk. The description model had the opposite problem — only the
 * guidance screen released it, so an edition without Guidance never released it at all.
 *
 * Screens hold a lease for as long as they may use an engine. The engines are released when the
 * last lease closes, and never before. Asking for the speech engine to start stays with each
 * screen, because starting is idempotent and harmless to repeat; releasing is not, and lives only
 * here.
 */
class SharedEngineOwner(
    /** What releasing means: stop what is being said, then free the engines. */
    private val releaseEngines: () -> Unit,
) {
    private var leases = 0

    val activeLeases: Int
        @Synchronized get() = leases

    @Synchronized
    fun acquire(): SharedEngineLease {
        leases++
        return SharedEngineLease(::onLeaseClosed)
    }

    @Synchronized
    private fun onLeaseClosed() {
        leases--
        if (leases == 0) releaseEngines()
    }
}

/** A screen's claim on the shared engines. Closing it more than once counts once. */
class SharedEngineLease internal constructor(
    private val onClosed: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) onClosed()
    }
}
