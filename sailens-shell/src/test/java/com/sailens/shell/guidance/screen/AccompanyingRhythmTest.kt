package com.sailens.shell.guidance.screen

import com.sailens.core.log.LogService
import com.sailens.guidance.delivery.GuidanceOutputSettings
import com.sailens.guidance.delivery.VisionFaultAlerts
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.PromptDeliveryChannels
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.PromptRevokeReasons
import com.sailens.guidance.processor.decision.SpeechScale
import com.sailens.output.Announcement
import com.sailens.shell.device.HapticArbiter
import com.sailens.shell.device.NavigationClaim
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A rhythm going with a prompt that own speech or the screen reader took, with interrupted prompts not retried: it
 * asks the motor as one that may wait, and an alarm or a fault rhythm holding the motor makes it wait. Whatever the
 * real [HapticArbiter] then does with it — replace it with a newer one, let it expire, drop it when navigation
 * vibration is turned off or when a hold begins, or play it — the prompt stays as it was when it was taken: delivered
 * once, on the channel that took it, its cooldown confirmed once. A rhythm that is its prompt's only channel and is
 * withdrawn when a hold begins takes its prompt back as held.
 */
class AccompanyingRhythmTest {

    private var now = 10_000L
    private val arbiter = HapticArbiter(clock = { now })
    private val outcomes = mutableListOf<PromptOutcomeTrace>()
    private val confirmed = mutableListOf<String>()
    private val revoked = mutableListOf<String>()
    private val reached = mutableListOf<String>()
    private val played = mutableListOf<String>()
    private var screenReader = false
    private var speech = true
    private val revokeReasons = mutableListOf<String>()
    private var drops = 0

    private val wiring = GuidanceOutputWiring(
        speakOwn = { _, _, _, _ -> true },
        settleFrame = { now },
        speechReady = { true },
        stopSpeech = {},
        announcementFor = { event ->
            Announcement(id = event.id.toString(), key = event.messageKey, text = event.messageKey, priority = SpeechScale.of(event), expiresAtMs = event.expiresAt)
        },
        eventText = { it.messageKey },
        noticeText = { it },
        announceToScreenReader = {},
        vibrateEvent = { true },
        // As HapticManager.triggerOrQueue asks the arbiter.
        vibrateEventAlone = { event, onQueuedResult ->
            arbiter.claimOrQueueNavigation(
                priority = SpeechScale.of(event),
                durationMs = RHYTHM_MS,
                expiresAtMs = event.expiresAt,
                start = { played += event.messageKey },
                onQueuedResult = onQueuedResult,
            )
        },
        // As HapticManager.dropQueuedNavigation asks the arbiter.
        dropQueuedNavigation = {
            drops++
            arbiter.dropQueuedNavigation()
        },
        playOrReserveVisionFault = { _, onPlayed -> onPlayed() },
        cancelVisionFaultReservation = {},
        cancelHaptics = { arbiter.stop() },
        descriptionHoldsTheFloor = { false },
        preemptDescription = {},
        confirmDelivered = { confirmed += it.messageKey },
        revokeUndelivered = { event, reason ->
            revoked += event.messageKey
            revokeReasons += reason
        },
        reportCut = {},
        reportHeardAfterCut = {},
        promptAccepted = { true },
        outputSettings = { GuidanceOutputSettings(speechEnabled = speech, screenReaderActive = screenReader, hapticsEnabled = true) },
        visionFaultSettings = { VisionFaultAlerts.Settings(speechEnabled = true, faultHapticsEnabled = true) },
        logger = SilentLog,
        recordOutcome = { outcomes += it },
        onReachedUser = { reached += it.messageKey },
    )

    /** An alarm holding the motor for [durationMs], as `HapticManager.play` claims it. */
    private fun alarm(durationMs: Long) = arbiter.claimUnconditionally(durationMs)

    private fun frame(vararg events: SceneEvent) = wiring.protocol.onFrame(events.toList(), 1, VisionFaults.NONE)

    /** HapticManager's timer, at [atMs]. */
    private fun timerAt(atMs: Long) {
        now = atMs
        arbiter.startReservedIfFree(now)
    }

    private fun assertDeliveredOnceAsTaken(vararg keys: String) {
        val channel = if (screenReader) PromptDeliveryChannels.SCREEN_READER else PromptDeliveryChannels.SPEECH
        assertEquals(keys.toList(), outcomes.map { it.messageKey })
        assertEquals(keys.map { listOf(channel) }, outcomes.map { it.deliveredVia })
        assertEquals(keys.toList(), confirmed)
        assertEquals(keys.toList(), reached)
        assertEquals(emptyList<String>(), revoked)
    }

    @Test
    fun `a waiting rhythm replaced by a newer one leaves its spoken prompt delivered`() {
        alarm(2_000)
        frame(event(SIDE, EventPriority.HIGH))
        now += 100
        frame(event(AHEAD, EventPriority.CRITICAL))
        assertDeliveredOnceAsTaken(SIDE, AHEAD)

        timerAt(now + 2_000)
        assertEquals(listOf(AHEAD), played)
        assertDeliveredOnceAsTaken(SIDE, AHEAD)
    }

    @Test
    fun `a waiting rhythm replaced by a newer one leaves its screen-reader prompt delivered`() {
        screenReader = true
        alarm(2_000)
        frame(event(SIDE, EventPriority.HIGH))
        now += 100
        frame(event(AHEAD, EventPriority.CRITICAL))
        assertDeliveredOnceAsTaken(SIDE, AHEAD)
    }

    @Test
    fun `a waiting rhythm that expires leaves its prompt delivered`() {
        alarm(5_000)
        frame(event(AHEAD, EventPriority.HIGH))
        timerAt(now + 5_000)
        assertEquals(emptyList<String>(), played)
        assertDeliveredOnceAsTaken(AHEAD)
    }

    @Test
    fun `a waiting rhythm dropped when navigation vibration is turned off leaves its prompt delivered`() {
        alarm(2_000)
        frame(event(AHEAD, EventPriority.HIGH))
        // As HapticManager.cancelNavigation does it.
        arbiter.dropQueuedNavigation()
        assertDeliveredOnceAsTaken(AHEAD)
    }

    @Test
    fun `a waiting rhythm that plays in the end is not counted again`() {
        alarm(500)
        frame(event(AHEAD, EventPriority.HIGH))
        timerAt(now + 500)
        assertEquals(listOf(AHEAD), played)
        assertDeliveredOnceAsTaken(AHEAD)
    }

    @Test
    fun `a waiting rhythm withdrawn when a hold begins never plays, and leaves its spoken or handed-over prompt delivered`() {
        for (reader in listOf(false, true)) {
            screenReader = reader
            outcomes.clear()
            confirmed.clear()
            reached.clear()
            alarm(2_000)
            frame(event(AHEAD, EventPriority.HIGH))
            wiring.protocol.holdBegan()
            assertEquals(1, drops)
            timerAt(now + 2_000)
            assertEquals("reader=$reader", emptyList<String>(), played)
            assertDeliveredOnceAsTaken(AHEAD)
            drops = 0
        }
    }

    @Test
    fun `a waiting rhythm that is the prompt's only channel, withdrawn when a hold begins, takes it back as held`() {
        speech = false
        alarm(2_000)
        frame(event(AHEAD, EventPriority.HIGH))
        assertEquals(emptyList<PromptOutcomeTrace>(), outcomes)
        wiring.protocol.holdBegan()
        timerAt(now + 2_000)
        assertEquals(emptyList<String>(), played)
        assertEquals(listOf(AHEAD), revoked)
        assertEquals(listOf(PromptRevokeReasons.SAFETY_HOLD), revokeReasons)
        assertEquals(PromptRevokeReasons.SAFETY_HOLD, outcomes.single().revokeReason)
        assertEquals(emptyList<String>(), confirmed)
        assertEquals(emptyList<String>(), reached)
    }

    @Test
    fun `a hold beginning with no rhythm waiting leaves the motor alone`() {
        frame(event(AHEAD, EventPriority.HIGH))
        assertEquals(listOf(AHEAD), played)
        wiring.protocol.holdBegan()
        assertEquals(0, drops)
    }

    private fun event(key: String, priority: EventPriority) = SceneEvent(
        timestamp = now,
        category = EventCategory.OBSTACLE,
        priority = priority,
        messageKey = key,
        expiresAt = now + 3_000,
        dedupeKey = key,
    )

    private object SilentLog : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private companion object {
        const val SIDE = "event_obstacle_left_person"
        const val AHEAD = "event_obstacle_center_person"
        const val RHYTHM_MS = 210L
    }
}
