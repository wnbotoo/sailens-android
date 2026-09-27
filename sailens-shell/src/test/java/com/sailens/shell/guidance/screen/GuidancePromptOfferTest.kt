package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.RoadSafetyState
import com.sailens.guidance.model.analysis.SceneElements
import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.analysis.WalkPathConnectivity
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.Severity
import com.sailens.guidance.model.trace.PromptDeliveryChannels.HAPTICS
import com.sailens.guidance.model.trace.PromptDeliveryChannels.SPEECH
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.PromptRevokeReasons
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import com.sailens.guidance.usecase.decision.RevokeUndeliveredEventUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole path a prompt takes from decision to the user while a description is playing, with the
 * real decision pipeline and cooldown: a prompt that waits is not delivered, is not left in the
 * cooldown, and is delivered once the description is over.
 */
class GuidancePromptOfferTest {

    private var now = 1_000L
    private val cooldown = CooldownManager()
    private val decide = DecideEventsUseCase(
        eventGenerator = EventGenerator(),
        conflictResolver = EventConflictResolver(),
        eventMerger = EventMerger(),
        cooldownManager = cooldown,
        deviceSensorRepository = StillSensors,
        clock = { now },
    )
    private val revoke = RevokeUndeliveredEventUseCase(cooldown)

    private val calls = mutableListOf<String>()
    private val outcomes = mutableListOf<PromptOutcomeTrace>()
    private var sequence = 0L
    private val outputs = GuidanceOutputSettings(speechEnabled = true, screenReaderActive = false, hapticsEnabled = true)

    /** One pipeline frame: decide, then offer the primary prompt and trace what became of it. */
    private fun frame(
        snapshot: SceneSnapshot,
        describing: Boolean,
        delivery: GuidanceDeliveryResult = GuidanceDeliveryResult(listOf(SPEECH, HAPTICS)),
    ): Boolean? {
        sequence++
        val primary = decide(snapshot).firstOrNull() ?: return null
        return offerAndTraceGuidancePrompt(
            event = primary,
            sourceSequenceNumber = sequence,
            outputs = outputs,
            descriptionHoldsTheFloor = describing,
            now = { WALL_CLOCK_BASE + now },
            preemptDescription = { calls += "preempt" },
            deliver = { calls += "deliver:${primary.messageKey}"; delivery },
            revoke = { calls += "revoke"; revoke(primary) },
            record = { outcomes += it },
        )
    }

    @Test
    fun `a low prompt waits for the description, stays out of the cooldown, then is delivered`() {
        val trafficLight = snapshot(trafficLight = true)
        assertEquals(EventPriority.LOW, decide(trafficLight).single().priority)
        cooldown.reset()

        assertEquals(false, frame(trafficLight, describing = true))
        now += 100
        // Offered again on the next frame: the revoke took the provisional cooldown record back.
        assertEquals(false, frame(trafficLight, describing = true))
        assertEquals(listOf("revoke", "revoke"), calls)

        now += 100
        assertEquals(true, frame(trafficLight, describing = false))
        assertEquals(listOf("revoke", "revoke", "preempt", "deliver:event_traffic_light"), calls)

        // Delivered for real now, so the cooldown holds it: no prompt offered, nothing recorded.
        now += 100
        assertEquals(null, frame(trafficLight, describing = false))

        // Exactly one outcome per offered prompt, each joined to the frame that offered it.
        assertEquals(listOf(1L, 2L, 3L), outcomes.map { it.sourceSequenceNumber })
        val (firstWait, secondWait, delivered) = outcomes
        for (wait in listOf(firstWait, secondWait)) {
            assertEquals(PromptRevokeReasons.WAITING_FOR_DESCRIPTION, wait.revokeReason)
            assertNull(wait.deliveredAt)
            assertEquals(emptyList<String>(), wait.deliveredVia)
        }
        assertEquals(WALL_CLOCK_BASE + 1_200L, delivered.deliveredAt)
        assertNull(delivered.revokedAt)
        assertEquals(listOf(SPEECH, HAPTICS), delivered.deliveredVia)
        assertEquals("event_traffic_light", delivered.messageKey)
        assertEquals("low", delivered.priority)
    }

    @Test
    fun `an urgent prompt preempts the description and is delivered at once`() {
        val dark = snapshot(frameQuality = FrameQuality.TOO_DARK)

        assertEquals(true, frame(dark, describing = true))
        assertEquals(listOf("preempt", "deliver:event_low_light"), calls)
        assertEquals(listOf(SPEECH, HAPTICS), outcomes.single().deliveredVia)
    }

    @Test
    fun `a prompt the speech engine refuses is revoked and offered again`() {
        val trafficLight = snapshot(trafficLight = true)

        assertEquals(false, frame(trafficLight, describing = false, delivery = GuidanceDeliveryResult.NOT_DELIVERED))
        now += 100
        assertTrue(
            "the refused prompt must not be sitting in the cooldown",
            frame(trafficLight, describing = false) == true,
        )

        assertEquals(PromptRevokeReasons.OUTPUT_REFUSED, outcomes[0].revokeReason)
        assertEquals(listOf(SPEECH, HAPTICS), outcomes[1].deliveredVia)
    }

    @Test
    fun `a prompt felt while the speech engine starts is traced as haptics only`() {
        // Speech and haptics are both on, as for a spoken prompt; the trace must still say "felt".
        frame(snapshot(trafficLight = true), describing = false, delivery = GuidanceDeliveryResult(listOf(HAPTICS)))

        val outcome = outcomes.single()
        assertEquals(listOf(HAPTICS), outcome.deliveredVia)
        assertTrue(outcome.speechEnabled && outcome.hapticsEnabled)
    }

    private fun snapshot(
        trafficLight: Boolean = false,
        frameQuality: FrameQuality = FrameQuality.OK,
    ) = SceneSnapshot(
        timestamp = now,
        obstacles = emptyList(),
        bottomCoverage = 1f,
        connectivity = WalkPathConnectivity(
            isBlocked = false,
            isNarrowing = false,
            suggestedBias = null,
            blockageConfidence = 0f,
            narrowingConfidence = 0f,
            blockageSeverity = Severity.NONE,
            narrowingSeverity = Severity.NONE,
            verticalReachRatio = 1f,
            validLayers = 3,
            totalLayers = 3,
            widthRetentionAvg = 1f,
            widthRetentionP25 = 1f,
            widthSlope = 0f,
            floodReachRatio = 1f,
            floodWidthRetentionP25 = 1f,
            floodVisitedRatio = 1f,
        ),
        sceneElements = SceneElements(hasTrafficLight = trafficLight),
        roadSafety = RoadSafetyState(false, false, 0f, false, false, 0f),
        groundTypeChange = null,
        frameQuality = frameQuality,
    )

    private object StillSensors : DeviceSensorRepository {
        override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)
        override val deviceRotationValue: Int = 0
        override val deviceRotationDegree: Int = 0
        override val isStationary: StateFlow<Boolean> = MutableStateFlow(false)
        override fun startObserving() = Unit
        override fun stopObserving() = Unit
    }

    private companion object {
        /** A wall-clock origin, so outcome times are plainly not the monotonic decision clock. */
        const val WALL_CLOCK_BASE = 1_700_000_000_000L
    }
}
