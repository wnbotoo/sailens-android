package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.shell.device.HapticArbiter
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

    /**
     * The motor, as HapticManager drives it: the real arbiter, and its timer that starts a reserved rhythm the
     * moment the motor is free. Null: nothing else ever plays, so a rhythm starts at once.
     */
    private var motor: Motor? = null

    private inner class Motor {
        val arbiter = HapticArbiter(clock = { now })

        /** A navigation rhythm, as `HapticManager.trigger` asks for it. */
        fun navigation(priority: EventPriority, durationMs: Long): Boolean = arbiter.tryClaim(priority.value, durationMs)

        /** HapticManager's timer. */
        fun tick() {
            arbiter.startReservedIfFree(now)
        }

        /** When `HapticManager.cancelNavigation` stopped the motor. */
        val stopped = mutableListOf<Long>()

        /** Navigation vibration turned off, as `HapticManager.cancelNavigation` does it. */
        fun navigationOff() {
            if (arbiter.releaseNavigation(now)) stopped += now
            arbiter.startReservedIfFree(now)
        }
    }

    private fun alertsWith(outputsLog: MutableList<Pair<Long, VisionFaultAlerts.Notice>>) = VisionFaultAlerts(
        outputs = object : VisionFaultAlerts.Outputs {
            override fun speak(notice: VisionFaultAlerts.Notice): Offer {
                outputsLog += now to notice
                return answer(notice)
            }

            override fun vibrate(priority: EventPriority, onPlayed: () -> Unit) {
                val start = {
                    vibrations += now to priority
                    onPlayed()
                }
                motor?.arbiter?.claimOrReserve(priority.value, VISION_UNRELIABLE_MS, start) ?: start()
            }

            override fun cancelVibration() {
                motor?.arbiter?.cancelReservation()
            }
        },
    )

    private val alerts = alertsWith(offers)

    private var settings = VisionFaultAlerts.Settings(speechEnabled = true, faultHapticsEnabled = true)

    /** Frames every 100 ms from now until (not including) [untilMs], all showing [faults]; the motor's timer every 10 ms. */
    private fun frames(untilMs: Long, faults: VisionFaults, onFrame: () -> Unit = {}) {
        while (now < untilMs) {
            alerts.update(now, faults, settings)
            onFrame()
            repeat(10) {
                now += 10
                motor?.tick()
            }
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
        // The first attempt may still be playing, and holds the floor up to its 6 s expiry, so it is offered
        // again only after that; one resend, and nothing more that cycle. The fallback does not stop it.
        assertEquals(listOf(0L, 6_100L, 15_000L, 21_100L), spoken(SceneEventMessageKeys.LOW_LIGHT).map { it.first })
    }

    @Test
    fun `a floor notice cut off by higher prompts gets two chances, then the fallback, and the cycle carries on`() {
        // Obstacle prompts keep the speaker: accepted twice, cut off, refused from then on.
        var accepted = 0
        answer = { if (accepted++ < 2) Offer.ACCEPTED else Offer.REFUSED }
        frames(75_000, FLOOR)

        val notices = spoken(SceneEventMessageKeys.GROUND_UNRECOGNIZED)
        assertEquals("taken at once, and resent once its window is over", listOf(0L, 10_100L), notices.take(2).map { it.first })
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
        answer = { Offer.PUBLISHED }
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

    // ---- Review round 1 ------------------------------------------------------------------------

    @Test
    fun `covered and dark taking turns cannot put the fallback off, whether the notice is accepted or refused`() {
        // P1-1: each change of kind used to restart the deadline. Speech never gets through, switch off.
        for (refuse in listOf(false, true)) {
            alerts.reset()
            vibrations.clear()
            now = 0
            answer = { if (refuse) Offer.REFUSED else Offer.ACCEPTED }
            settings = settings.copy(faultHapticsEnabled = false)
            while (now < 60_000) frames(now + 4_000, if ((now / 4_000) % 2 == 0L) DARK else COVERED)

            val at = vibrations.map { it.first }
            assertTrue("refuse=$refuse: first fallback by the first deadline, got $at", at.first() <= 6_000)
            assertTrue("refuse=$refuse: gaps $at", at.zipWithNext().all { (a, b) -> b - a <= 9_000 })
            assertTrue("refuse=$refuse: $at", at.size >= 6)
        }
    }

    @Test
    fun `failing and recovering again and again cannot put the fallback off either`() {
        // Each episode is shorter than the deadline; the debt survives the recovery between them.
        answer = { Offer.ACCEPTED }
        settings = settings.copy(faultHapticsEnabled = false)
        repeat(4) {
            frames(now + 4_000, COVERED)
            frames(now + 2_500, VisionFaults.NONE)
        }
        assertTrue("got ${vibrations.map { it.first }}", vibrations.isNotEmpty())
        assertTrue("by the second episode at the latest", vibrations.first().first <= 6_500 + 4_000)

        alerts.reset()
        vibrations.clear()
        repeat(4) {
            frames(now + 8_000, FLOOR)
            frames(now + 500, VisionFaults.NONE)
        }
        assertTrue("the floor too: ${vibrations.map { it.first }}", vibrations.isNotEmpty())
    }

    @Test
    fun `a notice heard out clears the debt, so a change of kind starts a new deadline`() {
        settings = settings.copy(faultHapticsEnabled = false)
        answer = { notice ->
            if (notice.messageKey == SceneEventMessageKeys.CAMERA_BLOCKED) alerts.onSpoken(notice.id)
            Offer.ACCEPTED
        }
        frames(3_000, COVERED)
        frames(10_000, DARK)
        assertEquals("dark's own deadline, 6 s after the change", listOf(9_000L), vibrations.map { it.first })
    }

    @Test
    fun `a fault rhythm never cuts an obstacle rhythm of equal or higher priority, and is played once it ends`() {
        // Round 1, P1-2, with the real motor arbiter: in one frame a CRITICAL obstacle rhythm is accepted,
        // then the floor enters (LOW).
        val motor = Motor().also { motor = it }
        assertTrue(motor.navigation(EventPriority.CRITICAL, 300))
        frames(1_000, FLOOR)
        assertEquals("entry rhythm after the obstacle rhythm, not over it", listOf(300L), vibrations.map { it.first })

        // The fallback too: it waits for a higher rhythm, then plays, still regardless of the switch.
        alerts.reset()
        vibrations.clear()
        settings = settings.copy(faultHapticsEnabled = false)
        val start = now
        frames(start + 9_900, FLOOR)
        assertTrue(motor.navigation(EventPriority.HIGH, 400))
        frames(start + 11_000, FLOOR)
        assertEquals(listOf(start + 10_300), vibrations.map { it.first })
    }

    @Test
    fun `with speech off a rhythm the motor refused is still owed`() {
        settings = VisionFaultAlerts.Settings(speechEnabled = false, faultHapticsEnabled = true)
        val motor = Motor().also { motor = it }
        assertTrue(motor.navigation(EventPriority.CRITICAL, 700))
        frames(1_000, DARK)
        assertEquals(listOf(700L), vibrations.map { it.first })
    }

    // ---- Review round 2 ------------------------------------------------------------------------

    /**
     * P1-1: the review's sequence. A frame every 2 s, each with an obstacle rhythm asked for first (centre,
     * front, side; HIGH then CRITICAL), the floor unrecognised throughout. Waiting for the next frame, the
     * LOW rhythm was refused every time: 0 in 600 s. Now it starts the moment the obstacle rhythm ends.
     */
    @Test
    fun `obstacle rhythms every frame cannot starve a fault rhythm, which comes as soon as the one playing ends`() {
        val obstacles = listOf(
            EventPriority.HIGH to 210L, EventPriority.CRITICAL to 210L, // ahead: two short
            EventPriority.HIGH to 360L, EventPriority.CRITICAL to 360L, // right: three short
            EventPriority.HIGH to 60L, EventPriority.CRITICAL to 60L, // left: one short
        )
        for (speechOn in listOf(false, true)) {
            now = 0
            alerts.reset()
            vibrations.clear()
            val motor = Motor().also { motor = it }
            // Speech off: each cycle's rhythm is the notice. Speech on, switch off, never heard: the fallback.
            settings = VisionFaultAlerts.Settings(speechEnabled = speechOn, faultHapticsEnabled = false)
            var accepted = 0
            var frame = 0
            while (now < 600_000) {
                val (priority, length) = obstacles[frame++ % obstacles.size]
                if (motor.navigation(priority, length)) accepted++
                alerts.update(now, FLOOR, settings)
                repeat(200) {
                    now += 10
                    motor.tick()
                }
            }
            val due = (0 until 10).map { it * 60_000L + if (speechOn) 10_000L else 0L }
            val played = vibrations.map { it.first }
            assertEquals("speechOn=$speechOn: one per cycle, $played", due.size, played.size)
            for ((owed, at) in due.zip(played)) {
                assertTrue("speechOn=$speechOn: owed at $owed, played at $at", at - owed in 0..360)
            }
            assertEquals("no obstacle rhythm refused: the fault rhythm ends before the next frame", 300, accepted)
        }
    }

    @Test
    fun `the fallback does not end the speech retries`() {
        // P1-2: a description holds the floor until 11 s; the fallback at 10 s must not stop the notice.
        settings = settings.copy(faultHapticsEnabled = false)
        answer = { if (now < 11_000) Offer.REFUSED else Offer.ACCEPTED }
        frames(12_000, FLOOR)
        assertEquals(listOf(10_000L), vibrations.map { it.first })
        assertEquals("said once the description is over", 11_000L, offers.last().first)
    }

    @Test
    fun `a resend at the edge of the first attempt's window is not lost to the fallback`() {
        // P1-2: as the speech ledger does, an attempt holds the floor up to and including its expiry, so a
        // resend at exactly 6 s would be refused. It comes just after, and the fallback does not cancel it.
        settings = settings.copy(faultHapticsEnabled = false)
        val holding = mutableListOf<VisionFaultAlerts.Notice>()
        answer = { notice ->
            if (holding.any { now <= it.expiresAtMs }) {
                Offer.REFUSED
            } else {
                holding += notice
                Offer.ACCEPTED
            }
        }
        frames(15_000, DARK)
        assertEquals(listOf(0L, 6_100L), holding.map { it.expiresAtMs - 6_000L })
        assertEquals(listOf(6_000L), vibrations.map { it.first })
    }

    @Test
    fun `a fallback waiting for the motor is dropped when the notice is heard first`() {
        // P2-3: owed at 6 s while the motor is busy until 7 s; the notice is heard at 6.2 s.
        val motor = Motor().also { motor = it }
        frames(5_900, DARK)
        assertTrue(motor.navigation(EventPriority.CRITICAL, 1_100))
        frames(6_200, DARK)
        alerts.onSpoken(offers.first().second.id)
        // No frame until the motor is free: hearing the notice alone has to drop the fallback.
        while (now < 7_500) {
            now += 10
            motor.tick()
        }
        frames(8_000, DARK)
        assertEquals("the entry rhythm only", listOf(0L), vibrations.map { it.first })
    }

    @Test
    fun `turning fault vibration off drops an entry rhythm still waiting, but never the fallback`() {
        // P2-4: entry with the switch on and the motor busy until 0.7 s; switched off at 0.2 s.
        val motor = Motor().also { motor = it }
        assertTrue(motor.navigation(EventPriority.CRITICAL, 700))
        frames(200, FLOOR)
        settings = settings.copy(faultHapticsEnabled = false)
        alerts.settingsChanged(settings)
        // No frame until well after the motor is free: the switch alone has to drop it.
        while (now < 1_000) {
            now += 10
            motor.tick()
        }
        assertEquals(emptyList<Any>(), vibrations)
        frames(11_000, FLOOR)
        assertEquals("no entry rhythm; the fallback at 10 s regardless", listOf(10_000L), vibrations.map { it.first })
    }

    @Test
    fun `notice ids differ between instances, so a late completion of another instance's notice counts for nothing`() {
        // P2-4: the speech engine is shared; a new screen's alerts must not take an old one's completion.
        val otherOffers = mutableListOf<Pair<Long, VisionFaultAlerts.Notice>>()
        val other = alertsWith(otherOffers)
        other.update(0, COVERED, settings)
        alerts.update(0, COVERED, settings)
        val mine = offers.single().second.id
        val theirs = otherOffers.single().second.id
        assertTrue("$mine vs $theirs", mine != theirs)

        settings = settings.copy(faultHapticsEnabled = false)
        alerts.onSpoken(theirs)
        vibrations.clear()
        now = 100
        frames(5_100, COVERED)
        assertEquals("still untold: the fallback comes", listOf(5_000L), vibrations.map { it.first })
    }

    // ---- Review round 3 ------------------------------------------------------------------------

    @Test
    fun `turning navigation vibration off does not cut a fault rhythm that has started`() {
        // P1-1: speech off, the floor's rhythm starts at 0; navigation vibration is turned off at 100 ms.
        // The motor used to be stopped for every pattern, the rhythm was cut, and nothing re-asked for it.
        val motor = Motor().also { motor = it }
        settings = VisionFaultAlerts.Settings(speechEnabled = false, faultHapticsEnabled = true)
        frames(100, FLOOR)
        assertEquals(listOf(0L), vibrations.map { it.first })
        motor.navigationOff()
        assertEquals("the fault rhythm plays on", emptyList<Long>(), motor.stopped)
        assertTrue("and still holds the motor", !motor.navigation(EventPriority.CRITICAL, 210))

        // A navigation rhythm playing is stopped, as before; a reservation waiting behind it then starts.
        now = 2_000
        assertTrue(motor.navigation(EventPriority.CRITICAL, 400))
        alerts.reset()
        vibrations.clear()
        alerts.update(now, DARK, settings)
        assertEquals("reserved behind the obstacle rhythm", emptyList<Any>(), vibrations)
        now = 2_100
        motor.navigationOff()
        assertEquals(listOf(2_100L), motor.stopped)
        assertEquals("starts as soon as the navigation rhythm is stopped", listOf(2_100L), vibrations.map { it.first })
    }

    @Test
    fun `a frame showing the floor recovered withdraws its waiting rhythm before the frame's obstacle rhythm asks`() {
        // P2-2: the fallback waits for the motor until 10.3 s; the frame at 10.3 s shows the floor recognised
        // and brings a CRITICAL obstacle rhythm. In the view model's order -- observe, the prompt, act -- the
        // stale floor rhythm must not start and refuse it.
        val motor = Motor().also { motor = it }
        settings = settings.copy(faultHapticsEnabled = false)
        frames(9_900, FLOOR)
        assertTrue(motor.navigation(EventPriority.CRITICAL, 400))
        frames(10_200, FLOOR)
        while (now < 10_290) {
            now += 10
            motor.tick()
        }
        now = 10_300
        alerts.observe(now, VisionFaults.NONE, settings)
        assertTrue("the obstacle rhythm is not refused for a stale floor rhythm", motor.navigation(EventPriority.CRITICAL, 210))
        alerts.act(now, settings)
        frames(12_000, VisionFaults.NONE)
        assertEquals(emptyList<Any>(), vibrations)
    }

    private companion object {
        /** VISION_UNRELIABLE's length: long, gap, short, gap, long. */
        const val VISION_UNRELIABLE_MS = 350L + 90 + 60 + 90 + 350

        val COVERED = VisionFaults(frameQuality = FrameQuality.OBSTRUCTED)
        val DARK = VisionFaults(frameQuality = FrameQuality.TOO_DARK)
        val FLOOR = VisionFaults(groundUnrecognized = true)
    }
}
