package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.RoadSafetyState
import com.sailens.guidance.model.analysis.SceneElements
import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.analysis.WalkPathConnectivity
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.Severity
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
import org.junit.Assert.assertFalse
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

    private fun frame(snapshot: SceneSnapshot, describing: Boolean): Boolean? {
        val primary = decide(snapshot).firstOrNull() ?: return null
        return offerGuidancePrompt(
            priority = primary.priority,
            descriptionHoldsTheFloor = describing,
            preemptDescription = { calls += "preempt" },
            deliver = { calls += "deliver:${primary.messageKey}"; true },
            revoke = { reason -> calls += "revoke:$reason"; revoke(primary) },
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
        assertEquals(listOf("revoke:waiting_for_description", "revoke:waiting_for_description"), calls)

        now += 100
        assertEquals(true, frame(trafficLight, describing = false))
        assertEquals(
            listOf("revoke:waiting_for_description", "revoke:waiting_for_description", "preempt", "deliver:event_traffic_light"),
            calls,
        )

        // Delivered for real now, so the cooldown holds it.
        now += 100
        assertEquals(null, frame(trafficLight, describing = false))
    }

    @Test
    fun `an urgent prompt preempts the description and is delivered at once`() {
        val dark = snapshot(frameQuality = FrameQuality.TOO_DARK)

        assertEquals(true, frame(dark, describing = true))
        assertEquals(listOf("preempt", "deliver:event_low_light"), calls)
    }

    @Test
    fun `a prompt the speech engine refuses is revoked and offered again`() {
        val trafficLight = snapshot(trafficLight = true)
        var accept = false
        val reasons = mutableListOf<String>()
        fun offer(): Boolean {
            val primary = decide(trafficLight).single()
            return offerGuidancePrompt(
                priority = primary.priority,
                descriptionHoldsTheFloor = false,
                preemptDescription = {},
                deliver = { accept },
                revoke = { reason -> reasons += reason; revoke(primary) },
            )
        }

        assertFalse(offer())
        now += 100
        accept = true
        assertTrue("the refused prompt must not be sitting in the cooldown", offer())
        assertEquals(listOf("output_refused"), reasons)
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
}
