package com.sailens.shell.guidance.screen

import com.sailens.guidance.delivery.VisionFaultAlerts
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.safety.DegradeReason
import com.sailens.output.SpeechEngineState
import com.sailens.shell.guidance.safety.FailureReason
import com.sailens.shell.guidance.safety.GuidanceSafetyState
import com.sailens.shell.guidance.safety.HoldCause
import com.sailens.shell.guidance.safety.InterruptReason
import com.sailens.shell.guidance.safety.OutputFault
import com.sailens.shell.guidance.safety.PromptHoldView
import com.sailens.shell.guidance.safety.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The status card's choice of sentence, tier by tier, before any word is looked up. */
class StatusCardContentTest {

    private val person = SceneEvent(
        timestamp = 0, category = EventCategory.OBSTACLE, priority = EventPriority.HIGH,
        messageKey = "event_obstacle_center_person", expiresAt = 3_000, dedupeKey = "obstacle_CENTER",
    )
    private val light = person.copy(category = EventCategory.TRAFFIC_LIGHT, priority = EventPriority.LOW, messageKey = "event_traffic_light")
    private val floorEvent = person.copy(
        category = EventCategory.GROUND_UNRECOGNIZED, priority = EventPriority.LOW, messageKey = SceneEventMessageKeys.GROUND_UNRECOGNIZED,
        dedupeKey = "ground_unrecognized",
    )

    private val ground = setOf(DegradeReason.GROUND_UNRECOGNIZED)
    private val floorTitle = StatusTitle.Paused(SceneEventMessageKeys.GROUND_UNRECOGNIZED)

    private fun card(
        error: Boolean = false,
        interrupted: Boolean = false,
        speechBroken: Boolean = false,
        loading: Boolean = false,
        running: Boolean = true,
        safety: GuidanceSafetyState = GuidanceSafetyState.Guiding,
        hold: PromptHoldView? = null,
        event: SceneEvent? = null,
    ) = statusCardContent(error, interrupted, speechBroken, loading, running, safety, hold, event)

    /** One state of Guidance with the screen's facts as they stand in it, and the card expected. */
    private data class Row(val name: String, val actual: StatusCardContent, val expected: StatusCardContent)

    private fun row(name: String, actual: StatusCardContent, title: StatusTitle, detail: StatusDetail, label: StatusLabel, alert: Boolean) =
        Row(name, actual, StatusCardContent(title, detail, label, alert))

    @Test
    fun `every state of Guidance, as the screen holds it, gives its card`() {
        val degraded = { perception: Set<DegradeReason>, output: Set<OutputFault> -> GuidanceSafetyState.Degraded(perception, output) }
        val rows = listOf(
            row("idle", card(running = false, safety = GuidanceSafetyState.Idle), StatusTitle.Idle, StatusDetail.IDLE, StatusLabel.STANDBY, false),
            row(
                "initializing, start pressed", card(loading = true, running = false, safety = GuidanceSafetyState.Initializing),
                StatusTitle.Starting, StatusDetail.STARTING, StatusLabel.STARTING, false,
            ),
            row(
                "initializing, running with no result yet", card(safety = GuidanceSafetyState.Initializing),
                StatusTitle.Starting, StatusDetail.STARTING, StatusLabel.STARTING, false,
            ),
            row(
                "initializing, back from the background", card(safety = GuidanceSafetyState.Initializing, hold = PromptHoldView(HoldCause.LIFECYCLE, stopping = false)),
                StatusTitle.Confirming, StatusDetail.HOLD_RECOVERING, StatusLabel.PAUSED, true,
            ),
            row("guiding", card(), StatusTitle.Clear, StatusDetail.CLEAR, StatusLabel.MONITORING, false),
            row("guiding, a person", card(event = person), StatusTitle.Event(person), StatusDetail.GUIDANCE, StatusLabel.LATEST_GUIDANCE, true),
            row("guiding, a traffic light", card(event = light), StatusTitle.Event(light), StatusDetail.GUIDANCE, StatusLabel.LATEST_GUIDANCE, false),
            row("floor unrecognised", card(safety = degraded(ground, emptySet())), floorTitle, StatusDetail.PATH_PAUSED, StatusLabel.PATH_PAUSED, false),
            row(
                "floor unrecognised, its own event current", card(safety = degraded(ground, emptySet()), event = floorEvent),
                floorTitle, StatusDetail.PATH_PAUSED, StatusLabel.PATH_PAUSED, false,
            ),
            row(
                "floor unrecognised, a person", card(safety = degraded(ground, emptySet()), event = person),
                StatusTitle.Event(person), StatusDetail.GUIDANCE, StatusLabel.LATEST_GUIDANCE, true,
            ),
            row(
                "floor unrecognised, a traffic light", card(safety = degraded(ground, emptySet()), event = light),
                StatusTitle.Event(light), StatusDetail.GUIDANCE, StatusLabel.LATEST_GUIDANCE, false,
            ),
            row(
                "speech not ready", card(safety = degraded(emptySet(), setOf(OutputFault.SPEECH_NOT_READY))),
                StatusTitle.Clear, StatusDetail.CLEAR, StatusLabel.MONITORING, false,
            ),
            row(
                "speech not ready, a person", card(safety = degraded(emptySet(), setOf(OutputFault.SPEECH_NOT_READY)), event = person),
                StatusTitle.Event(person), StatusDetail.GUIDANCE, StatusLabel.LATEST_GUIDANCE, true,
            ),
            row(
                "floor unrecognised, speech not ready", card(safety = degraded(ground, setOf(OutputFault.SPEECH_NOT_READY))),
                floorTitle, StatusDetail.PATH_PAUSED, StatusLabel.PATH_PAUSED, false,
            ),
            row(
                "speech unavailable", card(speechBroken = true, safety = degraded(emptySet(), setOf(OutputFault.SPEECH_UNAVAILABLE))),
                StatusTitle.SpeechUnavailable, StatusDetail.SPEECH_UNAVAILABLE, StatusLabel.ERROR, true,
            ),
            row(
                "floor unrecognised, speech unavailable", card(speechBroken = true, safety = degraded(ground, setOf(OutputFault.SPEECH_UNAVAILABLE))),
                StatusTitle.SpeechUnavailable, StatusDetail.SPEECH_UNAVAILABLE, StatusLabel.ERROR, true,
            ),
            row(
                "lens covered", card(safety = GuidanceSafetyState.StopRequired(StopReason.CAMERA_OBSTRUCTED), hold = PromptHoldView(HoldCause.CAMERA_OBSTRUCTED, stopping = true)),
                StatusTitle.HeldFault(SceneEventMessageKeys.CAMERA_BLOCKED), StatusDetail.HOLD_STOPPING, StatusLabel.PAUSED, true,
            ),
            row(
                "lens clear again, the stop held", card(safety = GuidanceSafetyState.StopRequired(StopReason.CAMERA_OBSTRUCTED), hold = PromptHoldView(HoldCause.CAMERA_OBSTRUCTED, stopping = false)),
                StatusTitle.Confirming, StatusDetail.HOLD_RECOVERING, StatusLabel.PAUSED, true,
            ),
            row(
                "too dark", card(safety = GuidanceSafetyState.StopRequired(StopReason.TOO_DARK), hold = PromptHoldView(HoldCause.TOO_DARK, stopping = true)),
                StatusTitle.HeldFault(SceneEventMessageKeys.LOW_LIGHT), StatusDetail.HOLD_STOPPING, StatusLabel.PAUSED, true,
            ),
            row(
                "no first result in time", card(safety = GuidanceSafetyState.StopRequired(StopReason.STARTUP_STALLED), hold = PromptHoldView(HoldCause.STARTUP_STALLED, stopping = true)),
                StatusTitle.HeldStall, StatusDetail.HOLD_STOPPING, StatusLabel.PAUSED, true,
            ),
            row(
                "results stopped coming", card(safety = GuidanceSafetyState.StopRequired(StopReason.PERCEPTION_STALLED), hold = PromptHoldView(HoldCause.PERCEPTION_STALLED, stopping = true)),
                StatusTitle.HeldStall, StatusDetail.HOLD_STOPPING, StatusLabel.PAUSED, true,
            ),
            row(
                "interrupted", card(interrupted = true, safety = GuidanceSafetyState.Interrupted(InterruptReason.LIFECYCLE), hold = PromptHoldView(HoldCause.LIFECYCLE, stopping = true)),
                StatusTitle.Interrupted, StatusDetail.INTERRUPTED, StatusLabel.ERROR, true,
            ),
            row(
                "failed", card(error = true, running = false, safety = GuidanceSafetyState.Failed(FailureReason.PIPELINE_FAILED)),
                StatusTitle.Error, StatusDetail.ERROR, StatusLabel.ERROR, true,
            ),
            row(
                "the screen running while the monitor judges nothing: no claim", card(safety = GuidanceSafetyState.Idle, event = person),
                StatusTitle.Starting, StatusDetail.STARTING, StatusLabel.STARTING, false,
            ),
        )
        for (row in rows) assertEquals(row.name, row.expected, row.actual)
    }

    @Test
    fun `the card says the way looks clear only while Guidance judges it in full`() {
        val states = listOf(
            GuidanceSafetyState.Idle, GuidanceSafetyState.Initializing, GuidanceSafetyState.Guiding,
            GuidanceSafetyState.StopRequired(StopReason.CAMERA_OBSTRUCTED), GuidanceSafetyState.Interrupted(InterruptReason.LIFECYCLE),
            GuidanceSafetyState.Failed(FailureReason.ERROR),
        ) + listOf(emptySet(), ground).flatMap { perception ->
            listOf(emptySet(), setOf(OutputFault.SPEECH_NOT_READY), setOf(OutputFault.SPEECH_UNAVAILABLE))
                .filter { perception.isNotEmpty() || it.isNotEmpty() }
                .map { GuidanceSafetyState.Degraded(perception, it) }
        }
        val holds = listOf(null) + HoldCause.entries.flatMap { listOf(PromptHoldView(it, stopping = true), PromptHoldView(it, stopping = false)) }
        val flags = listOf(false, true)
        var checked = 0
        for (safety in states) for (hold in holds) for (event in listOf(null, person, light, floorEvent)) {
            for (error in flags) for (interrupted in flags) for (speechBroken in flags) for (loading in flags) for (running in flags) {
                val content = card(error, interrupted, speechBroken, loading, running, safety, hold, event)
                val judges = safety == GuidanceSafetyState.Guiding || safety is GuidanceSafetyState.Degraded
                val judgesInFull = safety == GuidanceSafetyState.Guiding || (safety is GuidanceSafetyState.Degraded && safety.perception.isEmpty())
                val case = "$safety $hold $event error=$error interrupted=$interrupted speech=$speechBroken loading=$loading running=$running: $content"
                if (content.title == StatusTitle.Clear) assertTrue(case, judgesInFull)
                if (content.title is StatusTitle.Event) assertTrue(case, judges)
                if (safety is GuidanceSafetyState.Degraded && safety.perception.isNotEmpty() && content.title is StatusTitle.Event) {
                    assertTrue("only an ordinary prompt goes above a paused judgement: $case", (content.title as StatusTitle.Event).event != floorEvent)
                }
                checked++
            }
        }
        assertEquals(states.size * holds.size * 4 * 32, checked)
    }

    @Test
    fun `a paused floor is named by the sentence its notice is spoken with, for as long as it holds`() {
        assertEquals(VisionFaultAlerts.Fault.GROUND_UNRECOGNIZED.messageKey, floorTitle.messageKey)
        val floor = GuidanceSafetyState.Degraded(ground, emptySet())
        assertEquals("before its event", floorTitle, card(safety = floor).title)
        assertEquals("while its event is current", floorTitle, card(safety = floor, event = floorEvent).title)
        assertEquals("after its event expired", floorTitle, card(safety = floor, event = null).title)
        assertEquals("the floor recognised again, its event still current: as before", StatusTitle.Event(floorEvent), card(event = floorEvent).title)
    }

    @Test
    fun `a hold sits above the event and the clear way, below an error, an interruption and speech gone`() {
        val held = PromptHoldView(HoldCause.CAMERA_OBSTRUCTED, stopping = false)
        assertEquals("above an event the screen would not keep anyway", StatusTitle.Confirming, card(hold = held, event = person).title)
        assertEquals(StatusTitle.Error, card(error = true, hold = held).title)
        assertEquals(StatusDetail.ERROR, card(error = true, hold = held).detail)
        val interrupted = card(interrupted = true, hold = PromptHoldView(HoldCause.LIFECYCLE, stopping = true))
        assertEquals("an interruption is itself the stop", StatusCardContent(StatusTitle.Interrupted, StatusDetail.INTERRUPTED, StatusLabel.ERROR, alert = true), interrupted)
        assertEquals(StatusTitle.Idle, card(running = false, hold = held).title)
    }

    @Test
    fun `with speech gone the title is speech's and the detail still says prompts are paused`() {
        val stopping = card(speechBroken = true, hold = PromptHoldView(HoldCause.TOO_DARK, stopping = true))
        assertEquals(StatusCardContent(StatusTitle.SpeechUnavailable, StatusDetail.HOLD_STOPPING, StatusLabel.ERROR, alert = true), stopping)
        val recovering = card(speechBroken = true, hold = PromptHoldView(HoldCause.TOO_DARK, stopping = false))
        assertEquals(StatusDetail.HOLD_RECOVERING, recovering.detail)
    }

    @Test
    fun `no tier but the event's names an event`() {
        val tiers = listOf(
            card(error = true, event = person), card(interrupted = true, event = person), card(speechBroken = true, event = person),
            card(loading = true, event = person), card(running = false, event = person),
            card(hold = PromptHoldView(HoldCause.PERCEPTION_STALLED, stopping = true), event = person),
            card(safety = GuidanceSafetyState.Initializing, event = person),
        )
        for (content in tiers) assertFalse(content.toString(), content.title is StatusTitle.Event)
        assertTrue(card(event = person).title is StatusTitle.Event)
    }

    @Test
    fun `the screen's state gives the card its facts, speech counting as gone only when the session relies on it`() {
        val floor = GuidanceSafetyState.Degraded(ground, setOf(OutputFault.SPEECH_UNAVAILABLE))
        val relying = SceneAnalysisUiState(
            isRunning = true, isSpeechEnabled = true, isScreenReaderActive = false, speechEngineState = SpeechEngineState.UNAVAILABLE,
            safetyState = floor,
        )
        assertEquals(StatusTitle.SpeechUnavailable, relying.statusCardContent().title)
        assertEquals("a screen reader speaks instead", floorTitle, relying.copy(isScreenReaderActive = true).statusCardContent().title)
        assertEquals("speech is off", floorTitle, relying.copy(isSpeechEnabled = false).statusCardContent().title)
        assertEquals("the engine is only starting", floorTitle, relying.copy(speechEngineState = SpeechEngineState.INITIALIZING).statusCardContent().title)
        assertEquals("not running", StatusTitle.Idle, relying.copy(isRunning = false).statusCardContent().title)

        val ui = SceneAnalysisUiState(isRunning = true, safetyState = GuidanceSafetyState.Guiding, speechEngineState = SpeechEngineState.READY)
        assertEquals(StatusTitle.Clear, ui.statusCardContent().title)
        assertEquals(StatusTitle.Starting, ui.copy(safetyState = GuidanceSafetyState.Initializing).statusCardContent().title)
        assertEquals(StatusTitle.Starting, ui.copy(isRunning = false, isLoading = true).statusCardContent().title)
        assertEquals(StatusTitle.Event(person), ui.copy(activeStatusEvent = person).statusCardContent().title)
        assertEquals(StatusTitle.Confirming, ui.copy(promptHold = PromptHoldView(HoldCause.TOO_DARK, stopping = false)).statusCardContent().title)
        assertEquals(StatusTitle.Interrupted, ui.copy(wasInterrupted = true).statusCardContent().title)
        assertEquals(StatusTitle.Error, ui.copy(errorMessage = "failed").statusCardContent().title)
    }

    @Test
    fun `the paused floor's own words are in both languages`() {
        for (values in listOf("values", "values-zh")) {
            val xml = File("src/main/res/$values/strings.xml").readText()
            for (name in listOf("status_label_path_paused", "status_path_paused_detail", SceneEventMessageKeys.GROUND_UNRECOGNIZED)) {
                assertTrue("$values: no string named $name", Regex("""<string name="${Regex.escape(name)}"[^>]*>[^<]+</string>""").containsMatchIn(xml))
            }
        }
    }
}
