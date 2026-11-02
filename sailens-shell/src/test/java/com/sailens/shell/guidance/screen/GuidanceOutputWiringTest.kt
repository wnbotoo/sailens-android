package com.sailens.shell.guidance.screen

import com.sailens.core.log.LogService
import com.sailens.guidance.delivery.GuidanceOutputSettings
import com.sailens.guidance.delivery.VisionFaultAlerts
import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.model.trace.PromptDeliveryChannels
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.processor.decision.SpeechScale
import com.sailens.output.Announcement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell's side of the output protocol: what reaches each Android output, in what order, and what
 * a stop does. The rules themselves are tested with the protocol, in sailens-guidance.
 */
class GuidanceOutputWiringTest {

    private var now = 10_000L
    private val calls = mutableListOf<String>()
    private val announcementsSpoken = mutableListOf<Announcement>()
    private val onSpokenByKey = mutableMapOf<String, () -> Unit>()
    private val outcomes = mutableListOf<PromptOutcomeTrace>()

    private var screenReader = false
    private var speechEnabled = true
    private var faultHaptics = true
    private var describing = false
    private var motorStarts = true
    private var reservation: (() -> Unit)? = null

    private val wiring = GuidanceOutputWiring(
        speakOwn = { announcement, onSpoken, _, _ ->
            calls += "speak:${announcement.key}"
            announcementsSpoken += announcement
            onSpoken?.let { onSpokenByKey[announcement.key] = it }
            true
        },
        settleFrame = { now },
        speechReady = { true },
        stopSpeech = { calls += "stopSpeech" },
        announcementFor = { event ->
            Announcement(
                id = event.id.toString(),
                key = event.messageKey,
                text = "event text",
                priority = event.priority.value,
                expiresAtMs = event.expiresAt,
            )
        },
        eventText = { "text of ${it.messageKey}" },
        noticeText = { "text of $it" },
        announceToScreenReader = { calls += "reader:$it" },
        vibrateEvent = {
            calls += "vibrate:${it.messageKey}"
            true
        },
        playOrReserveVisionFault = { priority, onPlayed ->
            calls += "faultRhythm:$priority"
            if (motorStarts) onPlayed() else reservation = onPlayed
        },
        cancelVisionFaultReservation = {
            calls += "cancelReservation"
            reservation = null
        },
        cancelHaptics = { calls += "cancelHaptics" },
        descriptionHoldsTheFloor = { describing },
        preemptDescription = { calls += "preempt:$it" },
        confirmDelivered = { calls += "confirm" },
        revokeUndelivered = { _, _ -> calls += "revoke" },
        reportCut = { calls += "cut" },
        reportHeardAfterCut = { calls += "heardAfterCut" },
        promptAccepted = { true },
        outputSettings = {
            GuidanceOutputSettings(speechEnabled = speechEnabled, screenReaderActive = screenReader, hapticsEnabled = true)
        },
        visionFaultSettings = { VisionFaultAlerts.Settings(speechEnabled, faultHaptics) },
        logger = SilentLog,
        recordOutcome = { outcomes += it },
        onReachedUser = { calls += "reached:${it.messageKey}" },
    )

    @Test
    fun `own speech - the receipts come after the prompt was taken`() {
        wiring.protocol.onFrame(listOf(event()), 1, VisionFaults.NONE)

        assertEquals(
            listOf("preempt:$KEY", "speak:$KEY", "vibrate:$KEY", "confirm", "reached:$KEY"),
            calls,
        )
        assertEquals(listOf(PromptDeliveryChannels.SPEECH, PromptDeliveryChannels.HAPTICS), outcomes.single().deliveredVia)
    }

    @Test
    fun `screen reader - the event's copy goes to the announcer and own speech is not touched`() {
        screenReader = true

        wiring.protocol.onFrame(listOf(event()), 1, VisionFaults.NONE)

        assertEquals(
            listOf("preempt:$KEY", "reader:text of $KEY", "vibrate:$KEY", "confirm", "reached:$KEY"),
            calls,
        )
    }

    @Test
    fun `a notice goes to own speech at its priority with its deadline, and reports when it was heard out`() {
        wiring.protocol.onFrame(emptyList(), 1, COVERED)

        val notice = announcementsSpoken.single()
        assertEquals(SceneEventMessageKeys.CAMERA_BLOCKED, notice.key)
        assertEquals("text of ${SceneEventMessageKeys.CAMERA_BLOCKED}", notice.text)
        assertEquals(SpeechScale.of(EventPriority.CRITICAL), notice.priority)
        assertEquals(now + VisionFaultAlerts.Fault.OBSTRUCTED.deadlineMs, notice.expiresAtMs)
        assertTrue(SceneEventMessageKeys.CAMERA_BLOCKED in onSpokenByKey)
    }

    @Test
    fun `a notice goes to the announcer when the screen reader is on`() {
        screenReader = true

        wiring.protocol.onFrame(emptyList(), 1, COVERED)

        assertTrue("reader:text of ${SceneEventMessageKeys.CAMERA_BLOCKED}" in calls)
        assertEquals(emptyList<Announcement>(), announcementsSpoken)
    }

    @Test
    fun `below high a prompt or a notice waits for the description, as the shell's policy says`() {
        describing = true

        wiring.protocol.onFrame(listOf(event(priority = EventPriority.MEDIUM)), 1, FLOOR)

        assertEquals(listOf("revoke", "faultRhythm:LOW"), calls)
    }

    @Test
    fun `high and above stop the description first`() {
        describing = true

        wiring.protocol.onFrame(listOf(event(priority = EventPriority.HIGH)), 1, VisionFaults.NONE)

        assertEquals("preempt:$KEY", calls.first())
    }

    @Test
    fun `a switch change drops a rhythm still waiting, at once`() {
        motorStarts = false
        wiring.protocol.onFrame(emptyList(), 1, COVERED)
        calls.clear()

        faultHaptics = false
        wiring.protocol.settingsChanged()

        assertEquals(listOf("cancelReservation"), calls)
        assertNull(reservation)
    }

    @Test
    fun `a stop resets the alerts before anything else stops, and what the stop brings about is dropped`() {
        motorStarts = false
        wiring.protocol.onFrame(emptyList(), 1, COVERED)
        calls.clear()

        wiring.stop { calls += "during stop" }

        assertEquals(listOf("cancelReservation", "during stop", "stopSpeech", "cancelHaptics"), calls)

        // The engine reports the notice it was playing after all; nothing is left to hear about it.
        calls.clear()
        onSpokenByKey.getValue(SceneEventMessageKeys.CAMERA_BLOCKED)()
        now += 3_000
        wiring.protocol.onFrame(emptyList(), 2, VisionFaults.NONE)
        assertEquals(emptyList<String>(), calls)
        assertFalse("no receipts after a stop", calls.contains("confirm") || calls.contains("revoke"))
    }

    private fun event(priority: EventPriority = EventPriority.HIGH) = SceneEvent(
        timestamp = now,
        category = EventCategory.OBSTACLE,
        priority = priority,
        messageKey = KEY,
        expiresAt = now + 2_000,
        dedupeKey = KEY,
    )

    private object SilentLog : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private companion object {
        const val KEY = "event_obstacle_center_vehicle"
        val COVERED = VisionFaults(frameQuality = FrameQuality.OBSTRUCTED)
        val FLOOR = VisionFaults(groundUnrecognized = true)
    }
}
