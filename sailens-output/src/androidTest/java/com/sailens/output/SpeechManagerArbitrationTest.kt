package com.sailens.output

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sailens.core.log.LogService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The speech arbitration rules against the device's real TTS engine.
 *
 * Each rule is about what [SpeechManager.speak] returns, because that answer is what the Guidance
 * cooldown is allowed to count as delivered: a true for a prompt that is never spoken silences it
 * for a cooldown period.
 */
@RunWith(AndroidJUnit4::class)
class SpeechManagerArbitrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val speech = SpeechManager(instrumentation.targetContext, SilentLog)

    @After
    fun release() {
        instrumentation.runOnMainSync { speech.release() }
    }

    @Test
    fun anAnnouncementIsRefusedWhileTheEngineIsNotReady() {
        // Before: parked in a pending slot and reported accepted; the slot could then be
        // replaced, expire or be cleared by a failed init while the prompt's cooldown stood.
        assertFalse(speakOnMain(announcement("a", priority = 3)))
    }

    @Test
    fun onlyAStrictlyHigherPriorityInterruptsSpeechInProgress() {
        awaitReady()
        assertTrue(speakOnMain(announcement("critical", priority = 3, text = LONG_TEXT)))
        assertFalse("a lower priority must not cut it", speakOnMain(announcement("medium", priority = 1)))
        assertFalse("an equal priority must not cut it", speakOnMain(announcement("critical-2", priority = 3)))
        assertTrue("a strictly higher priority does", speakOnMain(announcement("higher", priority = 4)))
    }

    @Test
    fun aProtectedStatusNoticeCannotBeFlushedByAnOrdinaryPrompt() {
        awaitReady()
        instrumentation.runOnMainSync {
            speech.speakSystemNotice(LONG_TEXT, priority = Int.MAX_VALUE)
        }
        assertFalse(speakOnMain(announcement("critical", priority = 3)))
    }

    @Test
    fun anUnprotectedNoticeStaysInterruptible() {
        awaitReady()
        instrumentation.runOnMainSync { speech.speakSystemNotice(LONG_TEXT) }
        assertTrue(speakOnMain(announcement("critical", priority = 3)))
    }

    private fun speakOnMain(announcement: Announcement): Boolean {
        var accepted = false
        instrumentation.runOnMainSync { accepted = speech.speak(announcement) }
        return accepted
    }

    private fun awaitReady() {
        instrumentation.runOnMainSync { speech.initialize() }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!speech.isReady && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("TTS did not become ready on this device", speech.isReady)
    }

    private fun announcement(id: String, priority: Int, text: String = "Obstacle on the left.") = Announcement(
        id = id,
        key = id,
        text = text,
        priority = priority,
        expiresAtMs = SystemClock.elapsedRealtime() + 10_000,
    )

    private object SilentLog : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private companion object {
        const val LONG_TEXT = "Careful. A vehicle is directly ahead of you. Stop now and wait until it has passed."
    }
}
