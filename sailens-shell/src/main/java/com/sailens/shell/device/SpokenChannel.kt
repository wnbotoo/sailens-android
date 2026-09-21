package com.sailens.shell.device

import com.sailens.output.AccessibilityStatusProvider
import com.sailens.shell.guidance.settings.GuidanceSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Which channel spoken output currently goes through. The two audible ones are exclusive. */
enum class SpokenChannel {
    /** The app's own TTS engine. */
    OWN_TTS,

    /**
     * The screen reader. The app's own engine stays quiet so a sentence is never read twice, over
     * itself, in two voices.
     */
    SCREEN_READER,

    /** The user turned speech off and no screen reader is running. */
    SILENT,
    ;

    companion object {
        fun of(speechEnabled: Boolean, screenReaderActive: Boolean): SpokenChannel = when {
            screenReaderActive -> SCREEN_READER
            speechEnabled -> OWN_TTS
            else -> SILENT
        }
    }
}

/**
 * The current [SpokenChannel], derived once for the whole shell from the speech setting and the
 * screen-reader state, so every consumer sees the same answer at the same moment.
 */
class SpokenChannels(
    settingsStore: GuidanceSettingsStore,
    accessibilityStatusProvider: AccessibilityStatusProvider,
    scope: CoroutineScope,
) {
    val channel: StateFlow<SpokenChannel> = combine(
        settingsStore.settings,
        accessibilityStatusProvider.isScreenReaderActive,
    ) { settings, screenReaderActive ->
        SpokenChannel.of(settings.speechEnabled, screenReaderActive)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SpokenChannel.of(
            speechEnabled = settingsStore.settings.value.speechEnabled,
            screenReaderActive = accessibilityStatusProvider.isScreenReaderActive.value,
        ),
    )
}
