package com.sailens.shell.guidance.screen

/**
 * One-shot, visual-only effects of the guidance screen.
 *
 * Screen-reader announcements are deliberately not here. An effect is delivered only while this
 * screen is composed, and a navigation warning must reach TalkBack even when the Describe screen is
 * on top; announcements therefore go through [com.sailens.shell.device.ScreenReaderAnnouncer],
 * whose collector lives at the root.
 */
sealed interface SceneAnalysisUiEffect {
    data class ShowToast(val message: String) : SceneAnalysisUiEffect
}
