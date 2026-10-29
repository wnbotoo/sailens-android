package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.shell.guidance.screen.VisionFaultAlerts.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * det-first plan D5 (docs/detfirst-batch1-design, 2.12): each state reaches the user, keeps reaching
 * them while it lasts, and is never left silent because speech could not get through.
 */
class VisionFaultAlertsTest {

    private var now = 0L
    private val offers = mutableListOf<Pair<Long, VisionFaultAlerts.Notice>>()
    private val vibrations = mutableListOf<Pair<Long, EventPriority>>()

    /** What the spoken channel does with an offer; by default own TTS accepts it. */
    private var answer: (VisionFaultAlerts.Notice) -> Offer = { Offer.ACCEPTED }

    private val alerts = VisionFaultAlerts(
        outputs = object : VisionFaultAlerts.Outputs {
            override fun speak(notice: VisionFaultAlerts.Notice): Offer {
                offers += now to notice
                return answer(notice)
            }

            override fun vibrate(priority: EventPriority) {
                vibrations += now to priority
            }
        },
    )

    private var settings = VisionFaultAlerts.Settings(speechEnabled = true, faultHapticsEnabled = true)

    /** Frames every 100 ms from now until (not including) [untilMs], all showing [faults]. */
    private fun frames(untilMs: Long, faults: VisionFaults, onFrame: () -> Unit = {}) {
        while (now < untilMs) {
            alerts.update(now, faults, settings)
            onFrame()
            now += 100
        }
    }

    private fun spoken(key: String) = offers.filter { it.second.messageKey == key }

    @Test
    fun `a covered lens vibrates and speaks at once, and again every cycle, too dark vibrates only on entry`() {
        // Each accepted notice is heard out 1.2 s later.
        val playing = mutableMapOf<String, Long>()
        answer = { notice -> playing[notice.id] = now + 1_200; Offer.ACCEPTED }
        val hearOut = { playing.filterValues { it <= now }.keys.toList().forEach { playing.remove(it); alerts.onSpoken(it) } }

        frames(20_000, COVERED, hearOut)
        assertEquals(listOf(0L, 8_000L, 16_000L), vibrations.map { it.first })
        assertTrue(vibrations.all { it.second == EventPriority.CRITICAL })
        assertEquals(listOf(0L, 8_000L, 16_000L), spoken(SceneEventMessageKeys.CAMERA_BLOCKED).map { it.first })

        alerts.reset()
        vibrations.clear()
        offers.clear()
        val start = now
        frames(now + 40_000, DARK, hearOut)
        assertEquals("too dark: the entry rhythm only", listOf(start), vibrations.map { it.first })
        assertEquals(listOf(start, start + 15_000, start + 30_000), spoken(SceneEventMessageKeys.LOW_LIGHT).map { it.first })
    }

    @Test
    fun `engine ready but the notice never completes - the fallback comes at each deadline, whatever the switch`() {
        settings = settings.copy(faultHapticsEnabled = false)
        // Accepted every time, never reported as finished.
        frames(30_000, DARK)

        // No entry rhythm (switch off); a fallback at each cycle's 6 s deadline.
        assertEquals(listOf(6_000L, 21_000L), vibrations.map { it.first })
        // The first attempt may still be playing, so it is not offered again before the deadline; then
        // one resend, and nothing more that cycle.
        assertEquals(listOf(0L, 6_000L, 15_000L, 21_000L), spoken(SceneEventMessageKeys.LOW_LIGHT).map { it.first })
    }

    @Test
    fun `a floor notice cut off by higher prompts gets two chances, then the fallback, and the cycle carries on`() {
        // Obstacle prompts keep the speaker: accepted twice, cut off, refused from then on.
        var accepted = 0
        answer = { if (accepted++ < 2) Offer.ACCEPTED else Offer.REFUSED }
        frames(75_000, FLOOR)

        val notices = spoken(SceneEventMessageKeys.GROUND_UNRECOGNIZED)
        assertEquals("taken at once, and resent at the deadline", listOf(0L, 10_000L), notices.take(2).map { it.first })
        assertEquals("nothing more in the first cycle", 60_000L, notices[2].first)
        assertTrue("refused offers keep coming, no faster than every 500 ms", notices.size > 10)
        assertTrue(notices.zipWithNext().all { (a, b) -> b.first - a.first >= 500 })
        // Entry rhythm, the fallback at 10 s, then the next cycle's fallback at 70 s (no rhythm on its start).
        assertEquals(listOf(0L, 10_000L, 70_000L), vibrations.map { it.first })
        assertTrue(vibrations.all { it.second == EventPriority.LOW })
    }

    @Test
    fun `recovering during the fallback ends it, the recovery is offered once, and a late completion changes nothing`() {
        frames(8_000, COVERED)
        assertEquals("entry rhythm and the fallback at 5 s", listOf(0L, 5_000L), vibrations.map { it.first })
        val stale = spoken(SceneEventMessageKeys.CAMERA_BLOCKED).first().second.id

        // Clear from 8 s; recovered after the 2 s hold. The covered lens' next cycle (16 s) never comes.
        offers.clear()
        frames(30_000, VisionFaults.NONE)
        alerts.onSpoken(stale)
        frames(31_000, VisionFaults.NONE)

        assertEquals(listOf(0L, 5_000L), vibrations.map { it.first })
        assertEquals(listOf(SceneEventMessageKeys.CAMERA_RESTORED), offers.map { it.second.messageKey })
        assertEquals(10_000L, offers.single().first)
        assertEquals(EventPriority.LOW, offers.single().second.priority)
    }

    @Test
    fun `a recovery notice is dropped if not taken within 5 s`() {
        frames(1_000, COVERED)
        answer = { Offer.REFUSED }
        offers.clear()
        frames(20_000, VisionFaults.NONE)
        val restored = spoken(SceneEventMessageKeys.CAMERA_RESTORED).map { it.first }
        assertEquals(3_000L, restored.first())
        assertTrue(restored.all { it <= 8_000L })
    }

    @Test
    fun `clear for less than the hold is no recovery, and covered to dark says no recovery and plays no entry rhythm`() {
        // Covered, clear for 1.5 s, covered again: the same episode.
        frames(3_000, COVERED)
        frames(4_500, VisionFaults.NONE)
        frames(6_000, COVERED)
        assertEquals(emptyList<Any>(), spoken(SceneEventMessageKeys.CAMERA_RESTORED))
        assertEquals("entry only (the fallback at 5 s fell in the clear spell)", listOf(0L), vibrations.map { it.first }.filter { it != 5_000L })

        // Too dark straight after: its notice, no entry rhythm, no recovery.
        vibrations.clear()
        offers.clear()
        frames(7_000, DARK)
        assertEquals(listOf(SceneEventMessageKeys.LOW_LIGHT), offers.map { it.second.messageKey }.distinct())
        assertEquals(emptyList<Any>(), vibrations)
    }

    @Test
    fun `the fallback held during a clear spell comes as soon as the fault is back`() {
        frames(4_000, COVERED)
        frames(5_500, VisionFaults.NONE)
        frames(5_600, COVERED)
        assertEquals(listOf(0L, 5_500L), vibrations.map { it.first })
    }

    @Test
    fun `with speech off every cycle vibrates once and nothing is offered, whatever the switch`() {
        settings = VisionFaultAlerts.Settings(speechEnabled = false, faultHapticsEnabled = false)
        frames(31_000, DARK)
        frames(33_000, VisionFaults.NONE)
        assertEquals(listOf(0L, 15_000L, 30_000L), vibrations.map { it.first })
        assertEquals(emptyList<Any>(), offers)
    }

    @Test
    fun `the screen reader taking the notice counts as heard out`() {
        settings = settings.copy(faultHapticsEnabled = false)
        answer = { Offer.SPOKEN }
        frames(15_000, COVERED)
        assertEquals(emptyList<Any>(), vibrations)
        assertEquals(listOf(0L, 8_000L), spoken(SceneEventMessageKeys.CAMERA_BLOCKED).map { it.first })
    }

    @Test
    fun `the floor waits while frame quality is faulty, then enters or recovers from where it stands`() {
        val covered = VisionFaults(frameQuality = FrameQuality.OBSTRUCTED, groundUnrecognized = true)
        frames(3_000, covered)
        assertEquals(emptyList<Any>(), spoken(SceneEventMessageKeys.GROUND_UNRECOGNIZED))

        // Clear, the floor still unrecognised: camera recovery after the hold, and only then the floor enters.
        offers.clear()
        vibrations.clear()
        frames(6_000, FLOOR)
        assertEquals(5_000L, spoken(SceneEventMessageKeys.CAMERA_RESTORED).single().first)
        assertEquals("both are LOW; the floor notice takes its turn after the recovery", 5_000L, spoken(SceneEventMessageKeys.GROUND_UNRECOGNIZED).first().first)
        assertEquals(listOf(5_000L), vibrations.map { it.first })

        // Covered again while the floor is announced, then clear with the floor recognised: floor recovery.
        offers.clear()
        frames(8_000, covered)
        frames(11_000, VisionFaults.NONE)
        assertEquals(
            listOf(SceneEventMessageKeys.CAMERA_RESTORED, SceneEventMessageKeys.GROUND_RECOGNIZED),
            offers.map { it.second.messageKey }.filter { it != SceneEventMessageKeys.CAMERA_BLOCKED },
        )
    }

    @Test
    fun `a frame fault drops an unsaid recovery notice, the floor entering keeps the camera's`() {
        frames(1_000, FLOOR)
        answer = { Offer.REFUSED }
        frames(1_500, VisionFaults.NONE)
        offers.clear()
        frames(2_000, COVERED)
        frames(4_700, VisionFaults.NONE)
        assertEquals("the floor recovery went stale when the lens was covered", emptyList<Any>(), spoken(SceneEventMessageKeys.GROUND_RECOGNIZED))
    }

    @Test
    fun `fault vibration off and every notice heard out in time - no vibration at all`() {
        settings = settings.copy(faultHapticsEnabled = false)
        answer = { notice -> alerts.onSpoken(notice.id); Offer.ACCEPTED }
        frames(40_000, COVERED)
        frames(43_000, VisionFaults.NONE)
        frames(130_000, FLOOR)
        assertEquals(emptyList<Any>(), vibrations)
    }

    @Test
    fun `a floor notice waiting for a description - the description is not cut, the fallback comes at its deadline`() {
        // The view model refuses LOW notices while a description holds the floor, before speaking.
        val describing = { notice: VisionFaultAlerts.Notice -> notice.priority < EventPriority.HIGH }
        answer = { notice -> if (describing(notice)) Offer.REFUSED else Offer.ACCEPTED }
        settings = settings.copy(faultHapticsEnabled = false)
        frames(12_000, FLOOR)
        assertEquals(listOf(10_000L), vibrations.map { it.first })
    }

    private companion object {
        val COVERED = VisionFaults(frameQuality = FrameQuality.OBSTRUCTED)
        val DARK = VisionFaults(frameQuality = FrameQuality.TOO_DARK)
        val FLOOR = VisionFaults(groundUnrecognized = true)
    }
}
