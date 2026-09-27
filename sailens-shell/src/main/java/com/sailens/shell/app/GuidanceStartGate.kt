package com.sailens.shell.app

/**
 * Lets a tool hold off starting Guidance. Asked each time the person starts Guidance; a non-null
 * result is the reason, shown to them, and Guidance does not start.
 *
 * Only the debug build binds one (field capture: a capture must not record while a ZIP export is
 * reading and writing the same storage). Release builds bind none, so Guidance always starts.
 */
fun interface GuidanceStartGate {
    fun startBlockedReason(): String?
}
