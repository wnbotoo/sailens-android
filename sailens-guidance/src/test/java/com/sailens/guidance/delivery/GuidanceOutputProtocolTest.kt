package com.sailens.guidance.delivery

import com.sailens.core.log.LogService
import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.model.trace.GuidanceAlertChannels
import com.sailens.guidance.model.trace.GuidanceAlertKinds
import com.sailens.guidance.model.trace.GuidanceAlertResults
import com.sailens.guidance.model.trace.PromptDeliveryChannels
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.PromptRevokeReasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One frame's output: the order of what happens in it, and each sequence a covered lens, low light, an
 * unrecognised floor or a switch can bring about, through the real [VisionFaultAlerts].
 */
class GuidanceOutputProtocolTest {

    private var now = 10_000L
    private val calls = mutableListOf<String>()
    private val outcomes = mutableListOf<PromptOutcomeTrace>()
    private val reached = mutableListOf<SceneEvent>()

    private var speechEnabled = true
    private var screenReaderActive = false
    private var hapticsEnabled = true
    private var faultHapticsEnabled = true
    private var engineReady = true
    private var speechAccepts = true
    private var motorTakesPrompt = true
    private var motorStartsAtOnce = true
    private var descriptionWaitsBelow: EventPriority? = null

    private var outputReads = 0
    private var visionFaultReads = 0
    private val spokenCallbacks = mutableMapOf<String, () -> Unit>()
    private var reservedRhythm: (() -> Unit)? = null

    private var motorQueues = false
    private var motorAnswer: ((Boolean) -> Unit)? = null
    private var settledFrames = 0
    private val frameTimes = mutableListOf<Long?>()
    private val revokeReasons = mutableListOf<String>()
    private var onOutcome: ((SpeechOutcome) -> Unit)? = null
    private var rhythmGoesWithIt = true

    private val modeAttempts = mutableListOf<Long>()
    private var modeAccepts = true
    private var modeOutcome: ((SpeechOutcome) -> Unit)? = null

    private val alerts = mutableListOf<VisionAlert>()

    private fun protocolWith(retryInterrupted: Boolean, pathComplexOff: Boolean = false) = GuidanceOutputProtocol(
        speech = object : GuidanceSpeechPort {
            override val isReady: Boolean get() = engineReady

            override fun settleFrame(): Long {
                settledFrames++
                return now
            }

            override fun speak(event: SceneEvent, atMs: Long?, onOutcome: ((SpeechOutcome) -> Unit)?): Boolean {
                calls += "speak:${event.messageKey}"
                frameTimes += atMs
                if (speechAccepts) this@GuidanceOutputProtocolTest.onOutcome = onOutcome
                return speechAccepts
            }

            override fun speakModeNotice(id: String, messageKey: String, atMs: Long, expiresAtMs: Long, onOutcome: (SpeechOutcome) -> Unit): Boolean {
                calls += "mode"
                modeAttempts += now
                if (modeAccepts) this@GuidanceOutputProtocolTest.modeOutcome = onOutcome
                return modeAccepts
            }

            override fun speakNotice(notice: VisionFaultAlerts.Notice, atMs: Long?, onSpoken: () -> Unit): Boolean {
                frameTimes += atMs
                calls += "notice:${notice.messageKey}"
                if (speechAccepts) spokenCallbacks[notice.messageKey] = onSpoken
                return speechAccepts
            }
        },
        screenReader = object : GuidanceScreenReaderPort {
            override fun announce(event: SceneEvent) {
                calls += "reader:${event.messageKey}"
            }

            override fun announceNotice(messageKey: String) {
                calls += "readerNotice:$messageKey"
            }
        },
        motor = object : GuidanceMotorPort {
            override fun vibrate(event: SceneEvent): Boolean {
                calls += "vibrate:${event.messageKey}"
                return motorTakesPrompt
            }

            override fun vibrateAlone(event: SceneEvent, onQueuedResult: (played: Boolean) -> Unit): MotorOffer {
                calls += "vibrate:${event.messageKey}"
                motorAnswer = onQueuedResult
                return when {
                    motorQueues -> MotorOffer.QUEUED
                    motorTakesPrompt -> MotorOffer.PLAYED
                    else -> MotorOffer.REFUSED
                }
            }

            override fun vibrateVisionFault(priority: EventPriority, onPlayed: () -> Unit) {
                calls += "faultRhythm:$priority"
                if (motorStartsAtOnce) onPlayed() else reservedRhythm = onPlayed
            }

            override fun cancelVisionFaultVibration() {
                calls += "cancelFaultRhythm"
                reservedRhythm = null
            }
        },
        description = object : GuidanceDescriptionPort {
            override fun waits(priority: EventPriority): Boolean = descriptionWaitsBelow?.let { priority < it } ?: false

            override fun preempt(reason: String) {
                calls += "preempt:$reason"
            }
        },
        cooldown = object : GuidanceCooldownPort {
            override fun confirm(event: SceneEvent) {
                calls += "confirm"
            }

            override fun revoke(event: SceneEvent, reason: String) {
                calls += "revoke"
                revokeReasons += reason
            }

            override fun reportCut(event: SceneEvent) {
                calls += "cut"
            }

            override fun reportHeardAfterCut(event: SceneEvent) {
                calls += "heardAfterCut"
            }

            override fun accepted(event: SceneEvent): Boolean {
                calls += "accepted"
                return rhythmGoesWithIt
            }
        },
        settings = object : GuidanceSettingsPort {
            override fun output(): GuidanceOutputSettings {
                outputReads++
                return GuidanceOutputSettings(speechEnabled, screenReaderActive, hapticsEnabled)
            }

            override fun visionFaults(): VisionFaultAlerts.Settings {
                visionFaultReads++
                return VisionFaultAlerts.Settings(speechEnabled, faultHapticsEnabled)
            }
        },
        logger = SilentLog,
        recordOutcome = { outcomes += it },
        onReachedUser = { reached += it },
        wallClock = { WALL_CLOCK },
        retryInterrupted = retryInterrupted,
        pathComplexOff = pathComplexOff,
        recordAlert = { alerts += it },
    )

    private val protocol = protocolWith(retryInterrupted = false)

    private fun event() = SceneEvent(
        timestamp = now, category = EventCategory.OBSTACLE, priority = EventPriority.HIGH, messageKey = "event_obstacle_center_person",
        expiresAt = now + 3_000, dedupeKey = "obstacle_CENTER",
    )

    @Test
    fun `a frame settles the speaker first, and every offer in it is judged at the frame's time`() {
        protocol.onFrame(listOf(event()), 1, VisionFaults(FrameQuality.OBSTRUCTED, groundUnrecognized = false))
        assertEquals(1, settledFrames)
        assertEquals(listOf<Long?>(now, now), frameTimes)
    }

    @Test
    fun `off, a prompt is confirmed when taken and nobody asks what became of it`() {
        protocol.onFrame(listOf(event()), 1, VisionFaults.NONE)
        assertEquals(null, onOutcome)
        assertTrue("confirm" in calls)
        assertFalse("accepted" in calls)
    }

    @Test
    fun `on, a prompt own speech took waits for its outcome`() {
        val on = protocolWith(retryInterrupted = true)
        on.onFrame(listOf(event()), 1, VisionFaults.NONE)
        assertFalse("not yet", "confirm" in calls)
        assertTrue("taken is written when taken", outcomes.single().deliveredAt != null)
        assertEquals(listOf("speak:event_obstacle_center_person", "accepted", "vibrate:event_obstacle_center_person"), calls.filter { it != "preempt:event_obstacle_center_person" })
        onOutcome!!(SpeechOutcome.SPOKEN)
        assertEquals("confirm", calls.last())
    }

    @Test
    fun `on, a cut prompt goes to the cooldown, and a late end after it`() {
        val on = protocolWith(retryInterrupted = true)
        on.onFrame(listOf(event()), 1, VisionFaults.NONE)
        onOutcome!!(SpeechOutcome.CUT)
        assertEquals("cut", calls.last())
        onOutcome!!(SpeechOutcome.HEARD_AFTER_CUT)
        assertEquals("heardAfterCut", calls.last())
        assertFalse("confirm" in calls)
    }

    @Test
    fun `on, a prompt only saying again what was cut off does not vibrate again, and refused it is revoked as refused`() {
        val on = protocolWith(retryInterrupted = true)
        rhythmGoesWithIt = false
        on.onFrame(listOf(event()), 1, VisionFaults.NONE)
        assertFalse(calls.any { it.startsWith("vibrate") })
        assertEquals(listOf(PromptDeliveryChannels.SPEECH), outcomes.single().deliveredVia)

        calls.clear()
        speechAccepts = false
        hapticsEnabled = false
        on.onFrame(listOf(event()), 2, VisionFaults.NONE)
        assertFalse("accepted" in calls)
        assertEquals(listOf(PromptRevokeReasons.OUTPUT_REFUSED), revokeReasons)
    }

    @Test
    fun `on, the motor alone always plays and only then counts as taken`() {
        val on = protocolWith(retryInterrupted = true)
        speechEnabled = false
        rhythmGoesWithIt = false
        on.onFrame(listOf(event()), 1, VisionFaults.NONE)
        assertEquals(listOf("vibrate:event_obstacle_center_person", "accepted", "confirm"), calls.filter { !it.startsWith("preempt") })
    }

    @Test
    fun `a rhythm alone held off by a fault rhythm waits, with its receipts and its trace`() {
        speechEnabled = false
        motorQueues = true
        protocol.onFrame(listOf(event()), 1, VisionFaults.NONE)
        assertFalse("confirm" in calls)
        assertFalse("revoke" in calls)
        assertEquals(emptyList<PromptOutcomeTrace>(), outcomes)

        motorAnswer!!(true)
        assertEquals("confirm", calls.last())
        assertEquals(listOf(PromptDeliveryChannels.HAPTICS), outcomes.single().deliveredVia)
        assertEquals(1, reached.size)
    }

    @Test
    fun `a rhythm that waited and never played is taken back as dropped`() {
        speechEnabled = false
        motorQueues = true
        protocol.onFrame(listOf(event()), 1, VisionFaults.NONE)
        motorAnswer!!(false)
        assertEquals(listOf(PromptRevokeReasons.HAPTIC_DROPPED), revokeReasons)
        assertEquals(PromptRevokeReasons.HAPTIC_DROPPED, outcomes.single().revokeReason)
        assertEquals(emptyList<SceneEvent>(), reached)
    }

    @Test
    fun `a rhythm waiting when Guidance stops reports nothing`() {
        speechEnabled = false
        motorQueues = true
        protocol.onFrame(listOf(event()), 1, VisionFaults.NONE)
        protocol.reset()
        calls.clear()
        motorAnswer!!(true)
        assertEquals(emptyList<String>(), calls)
    }

    private fun modeFrames(on: GuidanceOutputProtocol, untilMs: Long, stepMs: Long = 100) {
        while (now < untilMs) {
            on.onFrame(emptyList(), 1, VisionFaults.NONE)
            now += stepMs
        }
    }

    @Test
    fun `the mode notice is said at the start, once heard no more, and not at all with the switch off`() {
        val on = protocolWith(retryInterrupted = true, pathComplexOff = true)
        on.onFrame(emptyList(), 1, VisionFaults.NONE)
        assertEquals(listOf(now), modeAttempts)
        now += 100
        on.onFrame(emptyList(), 2, VisionFaults.NONE)
        assertEquals("still saying it", 1, modeAttempts.size)
        modeOutcome!!(SpeechOutcome.SPOKEN)
        modeFrames(on, now + 20_000)
        assertEquals(1, modeAttempts.size)

        protocol.onFrame(emptyList(), 1, VisionFaults.NONE)
        assertEquals(1, modeAttempts.size)
    }

    @Test
    fun `refused it is offered again at most every 500 ms`() {
        val on = protocolWith(retryInterrupted = true, pathComplexOff = true)
        modeAccepts = false
        val start = now
        modeFrames(on, start + 1_600)
        assertEquals(listOf(start, start + 500, start + 1_000, start + 1_500), modeAttempts)
    }

    @Test
    fun `cut off it backs off by doubling up to 8 s, with no limit, until heard`() {
        val on = protocolWith(retryInterrupted = true, pathComplexOff = true)
        val start = now
        on.onFrame(emptyList(), 1, VisionFaults.NONE)
        val gaps = mutableListOf<Long>()
        var at = start
        repeat(5) {
            modeOutcome!!(SpeechOutcome.CUT)
            val count = modeAttempts.size
            while (modeAttempts.size == count) {
                now += 100
                on.onFrame(emptyList(), 1, VisionFaults.NONE)
            }
            gaps += modeAttempts.last() - at
            at = modeAttempts.last()
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 8_000L, 8_000L), gaps)
        modeOutcome!!(SpeechOutcome.HEARD_AFTER_CUT)
        modeFrames(on, now + 20_000)
        assertEquals(6, modeAttempts.size)
    }

    @Test
    fun `a screen reader takes it once, with speech off it is not said, and a stop starts it over`() {
        val reader = protocolWith(retryInterrupted = true, pathComplexOff = true)
        screenReaderActive = true
        modeFrames(reader, now + 1_000)
        assertEquals(1, calls.count { it == "readerNotice:${SceneEventMessageKeys.MODE_OBSTACLES_AND_BLOCKED}" })
        assertEquals(0, modeAttempts.size)

        screenReaderActive = false
        speechEnabled = false
        val silent = protocolWith(retryInterrupted = true, pathComplexOff = true)
        modeFrames(silent, now + 1_000)
        assertEquals(0, modeAttempts.size)

        speechEnabled = true
        val restarted = protocolWith(retryInterrupted = true, pathComplexOff = true)
        restarted.onFrame(emptyList(), 1, VisionFaults.NONE)
        val first = modeOutcome!!
        restarted.reset()
        now += 100
        restarted.onFrame(emptyList(), 1, VisionFaults.NONE)
        assertEquals("a new run says it again", 2, modeAttempts.size)
        first(SpeechOutcome.SPOKEN)
        now += 100
        restarted.onFrame(emptyList(), 1, VisionFaults.NONE)
        assertEquals("the old run's end does not count", 2, modeAttempts.size)
    }

    @Test
    fun `it waits for a description, as a MEDIUM prompt does`() {
        val on = protocolWith(retryInterrupted = true, pathComplexOff = true)
        descriptionWaitsBelow = EventPriority.HIGH
        modeFrames(on, now + 600)
        assertEquals(0, modeAttempts.size)
        descriptionWaitsBelow = null
        modeFrames(on, now + 600)
        assertEquals(1, modeAttempts.size)
    }

    @Test
    fun `its place on the scale is the same with ranks on and off`() {
        val scale = com.sailens.guidance.processor.decision.SpeechRanks.MODE_NOTICE_SCALE
        val s = com.sailens.guidance.processor.decision.SpeechScale
        assertEquals(15, s.of(EventPriority.MEDIUM, 7))
        assertEquals(15, scale)
        assertTrue("below any HIGH", scale < s.of(EventPriority.HIGH, 0))
        assertTrue("above any MEDIUM obstacle, rank on", scale > s.of(EventPriority.MEDIUM, 5))
        assertTrue("above any MEDIUM obstacle, rank off", scale > s.of(EventPriority.MEDIUM, 0))
    }

    @Test
    fun `on, an outcome from a stopped run reports nothing`() {
        val on = protocolWith(retryInterrupted = true)
        on.onFrame(listOf(event()), 1, VisionFaults.NONE)
        on.reset()
        calls.clear()
        onOutcome!!(SpeechOutcome.CUT)
        onOutcome!!(SpeechOutcome.SPOKEN)
        assertEquals(emptyList<String>(), calls)
    }

    private fun frame(vararg events: SceneEvent, faults: VisionFaults = VisionFaults.NONE, sequence: Long = 1) {
        protocol.onFrame(events.toList(), sequence, faults)
    }

    /** Frames every 100 ms up to, not including, [untilMs]. */
    private fun framesUntil(untilMs: Long, faults: VisionFaults) {
        while (now < untilMs) {
            frame(faults = faults)
            now += 100
        }
    }

    @Test
    fun `a frame with no prompt and no fault does nothing`() {
        frame()

        assertEquals(emptyList<String>(), calls)
        assertEquals(emptyList<PromptOutcomeTrace>(), outcomes)
    }

    @Test
    fun `a rhythm for a state that has ended is withdrawn first, the prompt goes next, the recovery is said last`() {
        motorStartsAtOnce = false
        frame(faults = FLOOR)
        assertEquals(
            listOf(
                "preempt:${SceneEventMessageKeys.GROUND_UNRECOGNIZED}",
                "notice:${SceneEventMessageKeys.GROUND_UNRECOGNIZED}",
                "faultRhythm:LOW",
            ),
            calls,
        )
        calls.clear()
        spokenCallbacks.clear()

        now += 100
        frame(obstacle())

        assertEquals(
            listOf(
                "cancelFaultRhythm",
                "preempt:$OBSTACLE_KEY",
                "speak:$OBSTACLE_KEY",
                "vibrate:$OBSTACLE_KEY",
                "confirm",
                "preempt:${SceneEventMessageKeys.GROUND_RECOGNIZED}",
                "notice:${SceneEventMessageKeys.GROUND_RECOGNIZED}",
            ),
            calls,
        )
    }

    @Test
    fun `only the primary prompt is offered`() {
        frame(obstacle(), obstacle(messageKey = "event_second"))

        assertEquals(listOf("preempt:$OBSTACLE_KEY", "speak:$OBSTACLE_KEY", "vibrate:$OBSTACLE_KEY", "confirm"), calls)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun `settings are read once for the prompt and twice for the alerts`() {
        frame(obstacle())

        assertEquals(1, outputReads)
        assertEquals(2, visionFaultReads)
    }

    @Test
    fun `a state event is confirmed and traced as the alerts', and the alerts say it once`() {
        val covered = obstacle(
            messageKey = SceneEventMessageKeys.CAMERA_BLOCKED,
            category = EventCategory.SENSOR_QUALITY,
            priority = EventPriority.CRITICAL,
        )

        frame(covered, faults = COVERED)

        assertEquals(
            listOf(
                "confirm",
                "preempt:${SceneEventMessageKeys.CAMERA_BLOCKED}",
                "notice:${SceneEventMessageKeys.CAMERA_BLOCKED}",
                "faultRhythm:CRITICAL",
            ),
            calls,
        )
        assertEquals(listOf(PromptDeliveryChannels.VISION_ALERTS), outcomes.single().deliveredVia)
        assertEquals("a hand-over is not what a replay repeats", emptyList<SceneEvent>(), reached)
    }

    @Test
    fun `a covered lens is felt every cycle, and at each deadline while its notice is not heard out`() {
        framesUntil(now + 16_100, COVERED)

        // Cycles at 0, 8 and 16 s; fallbacks at 5 and 13 s.
        assertEquals(5, calls.count { it == "faultRhythm:CRITICAL" })
    }

    @Test
    fun `low light vibrates on entry only while its notice is heard, and recovers after the hold`() {
        frame(faults = DARK)
        assertEquals(1, calls.count { it == "faultRhythm:HIGH" })
        spokenCallbacks.getValue(SceneEventMessageKeys.LOW_LIGHT)()

        now += 100
        framesUntil(now + 15_100, DARK)
        assertEquals(1, calls.count { it == "faultRhythm:HIGH" })

        calls.clear()
        framesUntil(now + 2_100, VisionFaults.NONE)
        assertEquals(1, calls.count { it == "notice:${SceneEventMessageKeys.LIGHT_RESTORED}" })
    }

    @Test
    fun `a notice never heard out brings the fallback at its deadline, whatever the fault vibration switch says`() {
        faultHapticsEnabled = false
        val start = now

        framesUntil(start + 9_900, FLOOR)
        assertEquals(0, calls.count { it == "faultRhythm:LOW" })

        framesUntil(start + 10_100, FLOOR)
        assertEquals(1, calls.count { it == "faultRhythm:LOW" })
    }

    @Test
    fun `a notice heard out before the deadline leaves nothing to fall back on`() {
        faultHapticsEnabled = false
        val start = now
        frame(faults = FLOOR)
        spokenCallbacks.getValue(SceneEventMessageKeys.GROUND_UNRECOGNIZED)()

        now += 100
        framesUntil(start + 20_000, FLOOR)

        assertEquals(0, calls.count { it == "faultRhythm:LOW" })
    }

    @Test
    fun `with speech off the rhythm is the notice, whatever the switch, and prompts are felt`() {
        speechEnabled = false
        faultHapticsEnabled = false

        frame(obstacle(), faults = COVERED)

        assertEquals(
            listOf("preempt:$OBSTACLE_KEY", "vibrate:$OBSTACLE_KEY", "confirm", "faultRhythm:CRITICAL"),
            calls,
        )
        assertEquals(listOf(PromptDeliveryChannels.HAPTICS), outcomes.single().deliveredVia)
        assertEquals(1, reached.size)
    }

    @Test
    fun `with everything off a prompt is on the status card only, which is not replayed`() {
        speechEnabled = false
        hapticsEnabled = false

        frame(obstacle())

        assertEquals(listOf(PromptDeliveryChannels.STATUS_CARD), outcomes.single().deliveredVia)
        assertEquals(emptyList<SceneEvent>(), reached)
    }

    @Test
    fun `the screen reader takes prompts and notices, and own speech is not touched`() {
        screenReaderActive = true

        frame(obstacle(), faults = COVERED)

        assertTrue("reader:$OBSTACLE_KEY" in calls)
        assertTrue("readerNotice:${SceneEventMessageKeys.CAMERA_BLOCKED}" in calls)
        assertFalse(calls.any { it.startsWith("speak:") || it.startsWith("notice:") })
        assertEquals(
            listOf(PromptDeliveryChannels.SCREEN_READER, PromptDeliveryChannels.HAPTICS),
            outcomes.single().deliveredVia,
        )
    }

    @Test
    fun `published to the screen reader counts as told, so no fallback follows`() {
        screenReaderActive = true
        faultHapticsEnabled = false
        val start = now

        framesUntil(start + 20_000, FLOOR)

        assertEquals(0, calls.count { it == "faultRhythm:LOW" })
    }

    @Test
    fun `a prompt that waits for the description is revoked and leaves the description alone`() {
        descriptionWaitsBelow = EventPriority.HIGH

        frame(obstacle(priority = EventPriority.MEDIUM))

        assertEquals(listOf("revoke"), calls)
        assertEquals(PromptRevokeReasons.WAITING_FOR_DESCRIPTION, outcomes.single().revokeReason)
    }

    @Test
    fun `an urgent prompt stops the description before it is spoken`() {
        descriptionWaitsBelow = EventPriority.HIGH

        frame(obstacle(priority = EventPriority.HIGH))

        assertEquals(listOf("preempt:$OBSTACLE_KEY", "speak:$OBSTACLE_KEY", "vibrate:$OBSTACLE_KEY", "confirm"), calls)
    }

    @Test
    fun `a notice follows the same rule about the description`() {
        descriptionWaitsBelow = EventPriority.HIGH

        frame(faults = FLOOR)
        assertFalse(calls.any { it.startsWith("notice:") || it.startsWith("preempt:") })

        calls.clear()
        now += 8_100
        frame(faults = COVERED)
        assertEquals(
            listOf("preempt:${SceneEventMessageKeys.CAMERA_BLOCKED}", "notice:${SceneEventMessageKeys.CAMERA_BLOCKED}"),
            calls.filterNot { it.startsWith("faultRhythm") || it == "cancelFaultRhythm" },
        )
    }

    @Test
    fun `a refused prompt is revoked and nothing vibrates`() {
        speechAccepts = false

        frame(obstacle())

        assertEquals(listOf("preempt:$OBSTACLE_KEY", "speak:$OBSTACLE_KEY", "revoke"), calls)
        assertEquals(PromptRevokeReasons.OUTPUT_REFUSED, outcomes.single().revokeReason)
        assertEquals(emptyList<SceneEvent>(), reached)
    }

    @Test
    fun `an engine not ready leaves the prompt to the motor, and the attempt still asks it to start`() {
        engineReady = false
        speechAccepts = false

        frame(obstacle())

        assertEquals(listOf("preempt:$OBSTACLE_KEY", "speak:$OBSTACLE_KEY", "vibrate:$OBSTACLE_KEY", "confirm"), calls)
        assertEquals(listOf(PromptDeliveryChannels.HAPTICS), outcomes.single().deliveredVia)
        assertEquals(1, reached.size)
    }

    @Test
    fun `the outcome carries the wall clock, the alerts run on the monotonic one`() {
        frame(obstacle(), sequence = 42)

        val outcome = outcomes.single()
        assertEquals(WALL_CLOCK, outcome.deliveredAt)
        assertEquals(42L, outcome.sourceSequenceNumber)
    }

    @Test
    fun `turning fault vibration off drops a rhythm still waiting, at once`() {
        motorStartsAtOnce = false
        frame(faults = DARK)
        assertEquals(1, calls.count { it == "faultRhythm:HIGH" })
        val reads = visionFaultReads

        faultHapticsEnabled = false
        protocol.settingsChanged()

        assertEquals(1, calls.count { it == "cancelFaultRhythm" })
        assertEquals(reads + 1, visionFaultReads)
    }

    @Test
    fun `after a reset a late completion counts for nothing and no recovery is said`() {
        motorStartsAtOnce = false
        frame(faults = COVERED)
        protocol.reset()
        assertEquals(1, calls.count { it == "cancelFaultRhythm" })
        calls.clear()

        spokenCallbacks.getValue(SceneEventMessageKeys.CAMERA_BLOCKED)()
        now += 100
        framesUntil(now + 3_000, VisionFaults.NONE)

        assertEquals(emptyList<String>(), calls)
    }

    // ---- the alert trace: what was said and felt about a fault, as the outputs answered it

    private fun notice(key: String, priority: EventPriority, channel: String, result: String, kind: String = GuidanceAlertKinds.FAULT_NOTICE) =
        VisionAlert(kind, key, priority, channel, result)

    private fun rhythm(priority: EventPriority, result: String) =
        VisionAlert(GuidanceAlertKinds.FAULT_RHYTHM, null, priority, GuidanceAlertChannels.HAPTICS, result)

    @Test
    fun `a covered lens is traced as its notice, then its rhythm asked for and played`() {
        frame(faults = COVERED)
        assertEquals(
            listOf(
                notice(SceneEventMessageKeys.CAMERA_BLOCKED, EventPriority.CRITICAL, GuidanceAlertChannels.SPEECH, GuidanceAlertResults.ACCEPTED),
                rhythm(EventPriority.CRITICAL, GuidanceAlertResults.REQUESTED),
                rhythm(EventPriority.CRITICAL, GuidanceAlertResults.PLAYED),
            ),
            alerts,
        )
    }

    @Test
    fun `a rhythm waiting for the motor is traced as played when it starts, and as withdrawn when its reason goes first`() {
        motorStartsAtOnce = false
        frame(faults = COVERED)
        reservedRhythm!!()
        assertEquals(
            listOf(GuidanceAlertResults.REQUESTED, GuidanceAlertResults.PLAYED),
            alerts.filter { it.kind == GuidanceAlertKinds.FAULT_RHYTHM }.map { it.result },
        )

        alerts.clear()
        protocol.reset()
        frame(faults = COVERED)
        now += 100
        // Clear again while the rhythm still waits: it is withdrawn, and never played.
        frame(faults = VisionFaults.NONE)
        assertEquals(
            listOf(GuidanceAlertResults.REQUESTED, GuidanceAlertResults.CANCELLED),
            alerts.filter { it.kind == GuidanceAlertKinds.FAULT_RHYTHM }.map { it.result },
        )
        assertEquals(null, reservedRhythm)
    }

    @Test
    fun `a notice is traced on the channel it was offered to, or none when nothing was offered`() {
        screenReaderActive = true
        frame(faults = COVERED)
        assertEquals(
            notice(SceneEventMessageKeys.CAMERA_BLOCKED, EventPriority.CRITICAL, GuidanceAlertChannels.SCREEN_READER, GuidanceAlertResults.PUBLISHED),
            alerts.first(),
        )

        alerts.clear()
        protocol.reset()
        screenReaderActive = false
        descriptionWaitsBelow = EventPriority.HIGH
        frame(faults = FLOOR)
        assertEquals(
            notice(SceneEventMessageKeys.GROUND_UNRECOGNIZED, EventPriority.LOW, GuidanceAlertChannels.NONE, GuidanceAlertResults.REFUSED),
            alerts.first(),
        )

        alerts.clear()
        protocol.reset()
        descriptionWaitsBelow = null
        speechAccepts = false
        frame(faults = DARK)
        assertEquals(
            notice(SceneEventMessageKeys.LOW_LIGHT, EventPriority.HIGH, GuidanceAlertChannels.SPEECH, GuidanceAlertResults.REFUSED),
            alerts.first(),
        )
    }

    @Test
    fun `a recovery is traced as a recovery`() {
        frame(faults = COVERED)
        now += 100
        framesUntil(now + 2_200, VisionFaults.NONE)
        assertEquals(
            listOf(notice(SceneEventMessageKeys.CAMERA_RESTORED, EventPriority.LOW, GuidanceAlertChannels.SPEECH, GuidanceAlertResults.ACCEPTED, GuidanceAlertKinds.FAULT_RECOVERY)),
            alerts.filter { it.kind == GuidanceAlertKinds.FAULT_RECOVERY },
        )
    }

    @Test
    fun `over a long run every notice and rhythm call has its one record, and nothing else does`() {
        motorStartsAtOnce = false
        framesUntil(now + 9_000, COVERED)
        reservedRhythm?.invoke()
        speechAccepts = false
        framesUntil(now + 3_000, DARK)
        speechAccepts = true
        framesUntil(now + 3_000, VisionFaults.NONE)
        screenReaderActive = true
        framesUntil(now + 12_000, FLOOR)
        protocol.reset()

        assertEquals(calls.count { it.startsWith("notice:") }, alerts.count { it.channel == GuidanceAlertChannels.SPEECH })
        assertEquals(calls.count { it.startsWith("readerNotice:") }, alerts.count { it.channel == GuidanceAlertChannels.SCREEN_READER })
        assertEquals(calls.count { it.startsWith("faultRhythm:") }, alerts.count { it.result == GuidanceAlertResults.REQUESTED })
        assertEquals(calls.count { it == "cancelFaultRhythm" }, alerts.count { it.result == GuidanceAlertResults.CANCELLED })
        assertTrue("the run asked for rhythms", alerts.any { it.result == GuidanceAlertResults.REQUESTED })
        assertTrue("and withdrew one", alerts.any { it.result == GuidanceAlertResults.CANCELLED })
        assertTrue("and refused a notice", alerts.any { it.result == GuidanceAlertResults.REFUSED })
        assertTrue("and published one", alerts.any { it.result == GuidanceAlertResults.PUBLISHED })
        assertTrue("and said a recovery", alerts.any { it.kind == GuidanceAlertKinds.FAULT_RECOVERY })
        assertTrue(alerts.none { it.channel == GuidanceAlertChannels.NONE })
    }

    private fun obstacle(
        messageKey: String = OBSTACLE_KEY,
        category: EventCategory = EventCategory.OBSTACLE,
        priority: EventPriority = EventPriority.HIGH,
    ) = SceneEvent(
        timestamp = now,
        category = category,
        priority = priority,
        messageKey = messageKey,
        expiresAt = now + 2_000,
        dedupeKey = messageKey,
    )

    private object SilentLog : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private companion object {
        const val OBSTACLE_KEY = "event_obstacle_center_vehicle"
        const val WALL_CLOCK = 1_700_000_000_000L
        val COVERED = VisionFaults(frameQuality = FrameQuality.OBSTRUCTED)
        val DARK = VisionFaults(frameQuality = FrameQuality.TOO_DARK)
        val FLOOR = VisionFaults(groundUnrecognized = true)
    }
}
