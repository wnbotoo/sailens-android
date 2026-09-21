package com.sailens.shell.device

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The one route to the screen reader, for every screen (architecture.md §6.6).
 *
 * An announcement needs an Android View, and a View belongs to whichever screen is composed. When
 * each screen collected its own announcements, a navigation warning raised while the Describe
 * screen was on top had no collector at all: the flow dropped it, and a TalkBack user heard the
 * description stop and then nothing. Announcements are therefore published here, by whoever has
 * something to say, and delivered by a single collector at the root that is composed for as long
 * as the app is.
 *
 * The buffer only covers the moment between publishing and the root collector running; it is not
 * a queue of stale speech, and overflow drops the oldest.
 */
class ScreenReaderAnnouncer {
    private val _announcements = MutableSharedFlow<String>(
        extraBufferCapacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val announcements: SharedFlow<String> = _announcements.asSharedFlow()

    fun announce(text: String) {
        _announcements.tryEmit(text)
    }

    private companion object {
        const val BUFFER = 8
    }
}
