package com.sailens.shell.app

import android.view.KeyEvent

/**
 * Lets a feature see hardware key presses before the activity handles them. The host activity
 * forwards `dispatchKeyEvent` here when one is bound; returning true consumes the event.
 *
 * Only the debug build binds one (field capture's "missed alert" marker on Volume Down). A handler
 * must consume only the keys it acts on, and only while it acts on them, so that normal volume
 * control keeps working otherwise.
 */
fun interface HardwareKeyHandler {
    fun onKeyEvent(event: KeyEvent): Boolean
}
