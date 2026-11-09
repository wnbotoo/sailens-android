package com.sailens.guidance.model.trace

import kotlin.math.ceil

data class SessionTraceMetadata(
    val sessionId: String,
    val startedAt: Long,
    val pipelineMode: String,
    val targetHardwareProfile: String,
    /**
     * When the trace session really opened, elapsed-realtime milliseconds: the start of the window the
     * trace can observe. Set by the trace service, not the caller. Not [startedAt], which is wall clock
     * and taken before the models initialise. Null in traces written before it existed.
     */
    val openedElapsedMs: Long? = null,
    /**
     * The trace records the safety state ([SafetyStateTrace]). Set by the trace service. False in a trace written
     * before these were: it then has no state timeline, which is not the same as one in which nothing changed.
     */
    val safetyTrace: Boolean = false,
    /**
     * The safety records carry the prompt hold ([SafetyStateTrace.hold], [SafetyStateTrace.holdReleased]) and frames
     * their receipt ([FrameTrace.receivedElapsedMs]). Set by the trace service. False in a trace written before these
     * were: no hold there means none was recorded, not that none held, and a frame without a receipt says nothing.
     */
    val holdTrace: Boolean = false,
)

data class FrameTrace(
    val sessionId: String,
    val sequenceNumber: Long,
    val frameTimestamp: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val droppedFramesSinceLast: Int,
    val processFrameMs: Long,
    val inferenceMs: Long,
    val analyzeSceneMs: Long,
    val decideEventsMs: Long,
    val totalPipelineMs: Long,
    val pipelineStartedAt: Long = 0,
    val pipelineCompletedAt: Long = 0,
    val cameraFrameIntervalMs: Long = 0,
    val pipelineOutputIntervalMs: Long = 0,
    val obstacleCount: Int,
    val eventCount: Int,
    val isBlocked: Boolean,
    val isNarrowing: Boolean,
    val isRoadDangerous: Boolean,
    val navigationPassableRatio: Double,
    val navigationPassableDelta: Double = 0.0,
    val roadRatio: Double = 0.0,
    val roadRatioDelta: Double = 0.0,
    val blockageConfidence: Double,
    val blockageConfidenceDelta: Double = 0.0,
    val blockageReason: String = "none",
    val verticalReachRatio: Double,
    val verticalReachDelta: Double = 0.0,
    val floodReachRatio: Double,
    val floodReachDelta: Double = 0.0,
    val widthRetentionP25: Double,
    val widthRetentionP25Delta: Double = 0.0,
    val occludedPassableRatio: Double = 0.0,
    val occludedPassableDelta: Double = 0.0,
    /** [com.sailens.guidance.model.analysis.GroundRecognition] 的小写名。 */
    val groundRecognition: String = "recognized",
    val unrecognizedGroundRatio: Double = 0.0,
    val rawObstacleDetectionCount: Int = 0,
    val obstacleCountDelta: Int = 0,
    val rawObstacleDetectionCountDelta: Int = 0,
    val rawObstacleClassNames: List<String> = emptyList(),
    val trackedObstacleCategories: List<String> = emptyList(),
    val roadVehicleSource: String = "none",
    val roadVehicleConfidence: Double = 0.0,
    val roadVehicleReason: String = "none",
    val roadVehicleBottomY: Double = 0.0,
    val roadVehicleCenterBandOverlap: Double = 0.0,
    val roadVehicleAreaRatio: Double = 0.0,
    val messageKeys: List<String>,
    val dominantClasses: List<String> = emptyList(),
    val dominantClassPercentages: List<String> = emptyList(),
    val semanticPreprocessMs: Long = 0,
    val semanticInferenceMs: Long = 0,
    val semanticOutputReadMs: Long = 0,
    val semanticPostprocessMs: Long = 0,
    val semanticAccelerator: String = "unknown",
    val semanticAcceleratorSelection: String = "unknown",
    val semanticPreprocessBackend: String = "unknown",
    val semanticPostprocessBackend: String = "unknown",
    val obstaclePreprocessMs: Long = 0,
    val obstacleInferenceMs: Long = 0,
    val obstacleOutputReadMs: Long = 0,
    val obstaclePostprocessMs: Long = 0,
    /** 本帧 obstacle 槽位是否运行了模型："det" / "none"（旧 trace 无此字段，解析为 "none"） */
    val obstacleRunKind: String = "none",
    val obstacleAccelerator: String = "unknown",
    val obstacleAcceleratorSelection: String = "unknown",
    val obstaclePreprocessBackend: String = "unknown",
    val obstaclePostprocessBackend: String = "unknown",
    /**
     * Camera geometry for this frame: "ok" / "degraded" / "unavailable"; "none" in traces
     * written before it existed. Issues are [com.sailens.guidance.geometry.GeometryIssue] names,
     * lower case. Nothing decides on these yet; they are here so recordings show what the
     * geometry would have been.
     */
    val geometryQuality: String = "none",
    val geometryIssues: List<String> = emptyList(),
    /** "camera_realtime" / "source_receipt" / "none". */
    val geometryTimeBasis: String = "none",
    val gravityAgeMs: Double? = null,
    /** Camera pitch below the horizon and roll (positive: gravity leans to image right), degrees. */
    val pitchDownDeg: Double? = null,
    val rollDeg: Double? = null,
    /** "calibration" / "focal_length_estimate" / "none". */
    val intrinsicsSource: String = "none",
    /** Horizontal focal length in upright analysis-image pixels. */
    val focalPx: Double? = null,
    /** Device heading (not walking direction) and yaw rate, positive turning right. */
    val headingDeg: Double? = null,
    val yawRateDegPerS: Double? = null,
    /** "ok" / "degraded" / "unavailable" / "none". */
    val orientationQuality: String = "none",
    /** The phone height the geometry assumed, metres, and where it came from ("default_seed" / "preset" / "calibrated" / "none"). */
    val phoneHeightM: Double? = null,
    val heightSource: String = "none",
    /** Whether the ground-contact distance was allowed to change levels in this session. */
    val groundContactEnabled: Boolean = false,
    /** Per tracked obstacle, today's level, the ground-contact estimate and the level used. */
    val obstacleDistances: List<ObstacleDistanceTrace> = emptyList(),
    /**
     * Frame quality after debouncing, whether or not sensor-quality events are on, and the frame's own
     * classification before debouncing: `FrameQuality` names, lower case. Null in traces written before
     * they existed.
     */
    val frameQuality: String? = null,
    val frameQualityClassified: String? = null,
    /**
     * The frame's qualification ("qualified" / "degraded" / "unqualified") and its reasons, lower case,
     * the reason it is unqualified first; see [com.sailens.guidance.safety.BaselineQualification]. Null
     * in traces written before it existed.
     */
    val qualification: String? = null,
    val qualificationReasons: List<String> = emptyList(),
    /** Per optional input, by [com.sailens.guidance.safety.GuidanceCapability] name in lower case, how it stood. */
    val capabilities: Map<String, CapabilityTrace> = emptyMap(),
    /**
     * Milliseconds from the frame source receiving the frame to the decision, both elapsed realtime. Not
     * the frame's whole age: exposure to receipt is not in it. Null when the source did not record the
     * receipt.
     */
    val receiptToDecisionMs: Double? = null,
    /**
     * When the frame source received the frame, elapsed-realtime milliseconds: the time the safety state judges a
     * result's freshness by. Null when the source did not record it, and in traces written before it existed.
     */
    val receivedElapsedMs: Long? = null,
)

/** One optional input's status ("not_configured" / "available" / "degraded" / "unavailable") and issues, lower case. */
data class CapabilityTrace(
    val status: String,
    val issues: List<String> = emptyList(),
)

/**
 * One tracked obstacle's distance evidence. Levels are `DistanceLevel` names, lower case;
 * [levelNew] is the level of [lowerBoundM], null without an interval. [upperBoundM] is null when
 * unbounded or without an interval. [contactValidity] is a `ContactValidity` name, lower case.
 */
data class ObstacleDistanceTrace(
    val category: String,
    val zone: String,
    val contactValidity: String,
    val lowerBoundM: Double? = null,
    val pointM: Double? = null,
    val upperBoundM: Double? = null,
    val levelToday: String,
    val levelNew: String? = null,
    val levelUsed: String,
)

data class OverlayRenderTrace(
    val sessionId: String,
    val renderedAt: Long,
    val renderMs: Long,
    val overlayMode: String,
    val bitmapRendered: Boolean,
    val sourceSequenceNumber: Long = 0,
    val sourcePipelineCompletedAt: Long = 0,
    val sourceAgeMs: Long = 0,
)

/**
 * What became of the one prompt a frame offered the user: delivered, or revoked and why.
 *
 * [FrameTrace.messageKeys] lists the frame's candidates after cooldown, which is not what the user
 * heard: only the first is offered, and it can still be held back for a description or refused by
 * an output that is busy with higher-priority speech. Labelling false alarms against candidates would
 * label prompts nobody received. This record is written by the output side after delivery is decided,
 * so it lands after its frame's record; join the two on [sourceSequenceNumber] (the frame record is
 * always written for a frame that offered a prompt, whatever the trace sampling).
 *
 * Invariants, enforced on construction so the writer and the parser share them:
 * - exactly one of [deliveredAt] and [revokedAt] is set; both are wall-clock milliseconds, the same
 *   clock as [FrameTrace.pipelineCompletedAt];
 * - delivered: [deliveredVia] names at least one channel from [PromptDeliveryChannels], and there is
 *   no [revokeReason];
 * - revoked: [revokeReason] is one of [PromptRevokeReasons], and [deliveredVia] is empty.
 *
 * [deliveredVia] is the evidence of what the user got — heard, felt or only shown. The output
 * settings are context, not evidence: with speech and haptics both on, a prompt can still reach the
 * user by vibration alone while the speech engine is starting.
 *
 * "Delivered" means the channel accepted the prompt. Spoken prompts can still be cut short later by
 * a strictly higher-priority one; that is not recorded here.
 */
data class PromptOutcomeTrace(
    /** Filled in by the trace service from its active session; callers on the output side leave it. */
    val sessionId: String = "",
    /** [com.sailens.guidance.model.scene.SceneEvent.id]. */
    val eventId: String,
    /** The frame that offered it ([FrameTrace.sequenceNumber]). */
    val sourceSequenceNumber: Long,
    val messageKey: String,
    val category: String,
    val priority: String,
    val deliveredAt: Long? = null,
    val revokedAt: Long? = null,
    /** Why it was revoked; null when delivered. One of [PromptRevokeReasons]. */
    val revokeReason: String? = null,
    /** The channels that accepted it, from [PromptDeliveryChannels]; empty when revoked. */
    val deliveredVia: List<String> = emptyList(),
    /** The output settings at the time. Context only; [deliveredVia] says what actually happened. */
    val speechEnabled: Boolean,
    val screenReaderActive: Boolean,
    val hapticsEnabled: Boolean,
) {
    init {
        require((deliveredAt == null) != (revokedAt == null)) {
            "A prompt outcome is either delivered or revoked: deliveredAt=$deliveredAt revokedAt=$revokedAt"
        }
        if (deliveredAt != null) {
            require(revokeReason == null) { "A delivered prompt has no revoke reason, got '$revokeReason'" }
            require(deliveredVia.isNotEmpty()) { "A delivered prompt names the channels that accepted it" }
            require(deliveredVia.all { it in PromptDeliveryChannels.all }) {
                "Unknown delivery channel in $deliveredVia"
            }
        } else {
            require(revokeReason in PromptRevokeReasons.all) { "Unknown revoke reason '$revokeReason'" }
            require(deliveredVia.isEmpty()) { "A revoked prompt reached no channel, got $deliveredVia" }
        }
    }
}

/** The closed set of [PromptOutcomeTrace.revokeReason] values. */
object PromptRevokeReasons {
    /** Not urgent, and a scene description held the floor; offered again later. */
    const val WAITING_FOR_DESCRIPTION = "waiting_for_description"

    /**
     * No enabled output channel accepted it: for example speech busy with an equal or higher
     * priority, the speech engine still starting with no other channel taking it, or the vibration
     * motor refusing a haptics-only prompt.
     */
    const val OUTPUT_REFUSED = "output_refused"

    /** Taken by own speech, then cut off before it was heard; offered again later. */
    const val CUT = "cut"

    /** Vibration was its only channel, it waited for the motor, and it never played. */
    const val HAPTIC_DROPPED = "haptic_dropped"

    val all: Set<String> = setOf(WAITING_FOR_DESCRIPTION, OUTPUT_REFUSED, CUT, HAPTIC_DROPPED)
}

/** The closed set of [PromptOutcomeTrace.deliveredVia] values. */
object PromptDeliveryChannels {
    /** The app's own text-to-speech spoke it. */
    const val SPEECH = "speech"

    /** Handed to the screen reader (TalkBack), which speaks it in the user's own settings. */
    const val SCREEN_READER = "screen_reader"

    /** Its vibration rhythm played. */
    const val HAPTICS = "haptics"

    /** Speech and haptics are both off; the status card is the channel the user chose. */
    const val STATUS_CARD = "status_card"

    /**
     * A covered lens, low light or an unrecognised floor: the shell's vision-fault
     * alerts say these states with their own cadence and fallback, so the event itself is not spoken.
     * Taken over, not heard: whether the user heard the alert is tracked there.
     */
    const val VISION_ALERTS = "vision_alerts"

    val all: Set<String> = setOf(SPEECH, SCREEN_READER, HAPTICS, STATUS_CARD, VISION_ALERTS)
}

/**
 * One alert the user was given or offered: a sentence or a rhythm about a fault, and what the output did
 * with it. A sentence carries [messageKey], a rhythm [haptic]; exactly one of them.
 *
 * Records reach the trace only while a session is open: an alert recorded while none is open is in no trace
 * and not counted, so [seq] and [SessionTraceSummary.guidanceAlertCount] prove a trace holds every alert its
 * session recorded, not every alert of a Guidance run.
 *
 * @param sessionId and [seq] are filled in by the trace service: callers leave them.
 * @param at wall-clock milliseconds, the clock of [FrameTrace.pipelineCompletedAt]; for lining up only.
 * @param elapsedMs elapsed-realtime milliseconds when it happened.
 * @param seq 1 for the session's first alert record, and one more for each after it.
 * @param priority an `EventPriority` name, lower case; [GuidanceAlertTrace.MAX_PRIORITY] for a system alert that
 *   nothing may cut.
 */
data class GuidanceAlertTrace(
    val sessionId: String = "",
    val seq: Long = 0,
    val at: Long,
    val elapsedMs: Long,
    val source: String,
    val kind: String,
    val messageKey: String? = null,
    val haptic: String? = null,
    val priority: String,
    val channel: String,
    val result: String,
) {
    init {
        require((messageKey == null) != (haptic == null)) {
            "An alert is a sentence or a rhythm: messageKey=$messageKey haptic=$haptic"
        }
        require(source in GuidanceAlertSources.all) { "Unknown alert source '$source'" }
        require(kind in GuidanceAlertKinds.all) { "Unknown alert kind '$kind'" }
        require(channel in GuidanceAlertChannels.all) { "Unknown alert channel '$channel'" }
        require(result in GuidanceAlertResults.all) { "Unknown alert result '$result'" }
    }

    companion object {
        /** The [priority] of a system alert above every prompt: a failure, an interruption, an alarm. */
        const val MAX_PRIORITY = "max"
    }
}

/** The closed set of [GuidanceAlertTrace.source] values. */
object GuidanceAlertSources {
    /** A covered lens, low light or an unrecognised floor, told by Guidance's delivery protocol. */
    const val VISION = "vision"

    /** A stall, a failure, an interruption or speech going unavailable, told by the shell. */
    const val SYSTEM = "system"

    val all: Set<String> = setOf(VISION, SYSTEM)
}

/** The closed set of [GuidanceAlertTrace.kind] values. */
object GuidanceAlertKinds {
    const val FAULT_NOTICE = "fault_notice"
    const val FAULT_RECOVERY = "fault_recovery"
    const val FAULT_RHYTHM = "fault_rhythm"
    const val FAILED = "failed"
    const val STALLED = "stalled"
    const val INTERRUPTED = "interrupted"
    const val SPEECH_UNAVAILABLE = "speech_unavailable"

    val all: Set<String> = setOf(FAULT_NOTICE, FAULT_RECOVERY, FAULT_RHYTHM, FAILED, STALLED, INTERRUPTED, SPEECH_UNAVAILABLE)
}

/** The closed set of [GuidanceAlertTrace.channel] values: where the alert was offered. */
object GuidanceAlertChannels {
    const val SPEECH = "speech"
    const val SCREEN_READER = "screen_reader"
    const val HAPTICS = "haptics"

    /** Offered to no output: speech is off, or a scene description holds the floor. */
    const val NONE = "none"

    val all: Set<String> = setOf(SPEECH, SCREEN_READER, HAPTICS, NONE)
}

/** The closed set of [GuidanceAlertTrace.result] values. */
object GuidanceAlertResults {
    /** Own speech took it; whether it was heard to the end is not recorded here. */
    const val ACCEPTED = "accepted"

    /** Handed to the screen reader, which reports no completion. */
    const val PUBLISHED = "published"

    /** Nothing took it; for a system alert's rhythm, the phone has no vibrator. */
    const val REFUSED = "refused"

    /**
     * A rhythm asked of the motor, which plays it at once or as soon as a rhythm it may not cut ends; or a system
     * notice handed to own speech, which reports nothing back for it.
     */
    const val REQUESTED = "requested"

    /** The rhythm started. */
    const val PLAYED = "played"

    /** A requested rhythm withdrawn before it started. */
    const val CANCELLED = "cancelled"

    val all: Set<String> = setOf(ACCEPTED, PUBLISHED, REFUSED, REQUESTED, PLAYED, CANCELLED)
}

/**
 * Where Guidance stood for the user, and the facts it was derived from, written by the shell whenever a fact or the
 * state changed. The state is the shell's `GuidanceSafetyState`; the names are lower case.
 *
 * Records reach the trace only while a session is open; the latest one is kept meanwhile, and written again as the
 * session's first ([cause] [SafetyTraceNames.Causes.SESSION_START], [seq] 1) when one opens, so a session's timeline
 * starts with its first line. [seq] and [SessionTraceSummary.safetyStateCount] let a reader find the records the
 * trace's bound dropped.
 *
 * @param sessionId and [seq] are filled in by the trace service: callers leave them.
 * @param at wall-clock milliseconds, the clock of [FrameTrace.pipelineCompletedAt]; for lining up only, never a duration.
 * @param elapsedMs elapsed-realtime milliseconds when this was observed.
 * @param stateSinceElapsedMs elapsed realtime since when [state] with [reasons] has held, which may be before the
 *   session: a record that only changes a fact keeps it.
 * @param state one of [SafetyTraceNames.States].
 * @param reasons the state's reasons: the perception reasons then the output faults for a degraded state, the one
 *   reason of a stop, an interruption or a failure, none otherwise. An open set.
 * @param judgement the latest result's qualification level (`qualified` / `degraded` / `unqualified`), null before
 *   the first result; [judgementReasons] its reasons.
 * @param outputFaults the output's health, by the speech engine's state. An open set.
 * @param hold ordinary prompts held after a stop, and how far the recovery has come; null when none are.
 * @param holdReleased on the record of the result that ended a hold: its evidence, that result counted in. Only
 *   there: a session's opening record, written again from it, has none.
 */
data class SafetyStateTrace(
    val sessionId: String = "",
    val seq: Long = 0,
    val at: Long,
    val elapsedMs: Long,
    val stateSinceElapsedMs: Long,
    val state: String,
    val reasons: List<String> = emptyList(),
    val phase: String,
    val interrupted: Boolean,
    val stall: String,
    val judgement: String? = null,
    val judgementReasons: List<String> = emptyList(),
    val outputFaults: List<String> = emptyList(),
    val expectedNavigationChannel: String,
    val cause: String,
    val hold: HoldTrace? = null,
    val holdReleased: HoldReleaseTrace? = null,
) {
    init {
        require(state in SafetyTraceNames.States.all) { "Unknown safety state '$state'" }
        require(holdReleased == null || (hold == null && cause == SafetyTraceNames.Causes.RESULT)) {
            "A hold is released by a result, and is gone after it: cause '$cause', hold $hold"
        }
        require(phase in SafetyTraceNames.Phases.all) { "Unknown run phase '$phase'" }
        require(stall in SafetyTraceNames.Stalls.all) { "Unknown stall condition '$stall'" }
        require(expectedNavigationChannel in SafetyTraceNames.Channels.all) { "Unknown navigation channel '$expectedNavigationChannel'" }
        require(cause in SafetyTraceNames.Causes.all) { "Unknown safety state cause '$cause'" }
        require(judgement == null || judgement in SafetyTraceNames.JUDGEMENTS) { "Unknown judgement '$judgement'" }
        require(judgement != null || judgementReasons.isEmpty()) { "Judgement reasons without a judgement: $judgementReasons" }
    }

    /** The state with its reasons, as one label: `degraded(ground_unrecognized)`, or `guiding`. Equal labels are the same state. */
    val stateLabel: String
        get() = if (reasons.isEmpty()) state else reasons.sorted().joinToString(prefix = "$state(", postfix = ")", separator = ",")
}

/**
 * Ordinary prompts held after a stop ([SafetyStateTrace.hold]).
 *
 * @param cause the stop that began it, one of [SafetyTraceNames.HoldCauses].
 * @param sinceElapsedMs when that stop began: a result whose frame was received at or before it is stale.
 * @param stopping the stop's cause still holds; false once it has gone and fresh results are awaited.
 * @param freshResults the fresh results counted since; [freshSpanMs] how far apart the first and last were received.
 * @param receiptFallbacks how many of those had no recorded receipt and counted at their arrival.
 * @param lastFreshSequenceNumber the frame of the latest counted result: a frame's `sequenceNumber`, not a record's `seq`.
 */
data class HoldTrace(
    val cause: String,
    val sinceElapsedMs: Long,
    val stopping: Boolean,
    val freshResults: Int,
    val freshSpanMs: Long,
    val firstFreshReceivedElapsedMs: Long? = null,
    val lastFreshReceivedElapsedMs: Long? = null,
    val receiptFallbacks: Int = 0,
    val lastFreshSequenceNumber: Long? = null,
) {
    init {
        require(cause in SafetyTraceNames.HoldCauses.all) { "Unknown hold cause '$cause'" }
    }
}

/**
 * What ended a hold ([SafetyStateTrace.holdReleased]), the releasing result counted in: as [HoldTrace], with
 * [sourceSequenceNumber] the frame of that result.
 */
data class HoldReleaseTrace(
    val cause: String,
    val sinceElapsedMs: Long,
    val freshResults: Int,
    val firstFreshReceivedElapsedMs: Long,
    val lastFreshReceivedElapsedMs: Long,
    val freshSpanMs: Long,
    val receiptFallbacks: Int,
    val sourceSequenceNumber: Long,
) {
    init {
        require(cause in SafetyTraceNames.HoldCauses.all) { "Unknown hold cause '$cause'" }
    }
}

/** The closed sets of names a [SafetyStateTrace] carries. */
object SafetyTraceNames {
    object States {
        const val IDLE = "idle"
        const val INITIALIZING = "initializing"
        const val GUIDING = "guiding"
        const val DEGRADED = "degraded"
        const val STOP_REQUIRED = "stop_required"
        const val INTERRUPTED = "interrupted"
        const val FAILED = "failed"

        val all: Set<String> = setOf(IDLE, INITIALIZING, GUIDING, DEGRADED, STOP_REQUIRED, INTERRUPTED, FAILED)
    }

    object Phases {
        const val NOT_STARTED = "not_started"
        const val STARTING = "starting"
        const val AWAITING_FIRST_RESULT = "awaiting_first_result"
        const val RUNNING = "running"
        const val FAILED = "failed"

        val all: Set<String> = setOf(NOT_STARTED, STARTING, AWAITING_FIRST_RESULT, RUNNING, FAILED)
    }

    object Stalls {
        const val NONE = "none"
        const val STARTUP_TIMEOUT = "startup_timeout"
        const val NO_RESULTS = "no_results"

        val all: Set<String> = setOf(NONE, STARTUP_TIMEOUT, NO_RESULTS)
    }

    object Channels {
        const val SCREEN_READER = "screen_reader"
        const val SPEECH = "speech"
        const val HAPTICS_ONLY = "haptics_only"
        const val STATUS_CARD_ONLY = "status_card_only"
        const val NONE = "none"

        val all: Set<String> = setOf(SCREEN_READER, SPEECH, HAPTICS_ONLY, STATUS_CARD_ONLY, NONE)
    }

    /** The input that produced the record. */
    object Causes {
        const val START_REQUESTED = "start_requested"
        const val STALL_WATCH_STARTED = "stall_watch_started"
        const val FLOW_STARTED = "flow_started"
        const val RESULT = "result"
        const val FLOW_FAILED = "flow_failed"
        const val STOPPED = "stopped"
        const val LIFECYCLE_STOP = "lifecycle_stop"
        const val LIFECYCLE_START = "lifecycle_start"
        const val OUTPUT = "output"
        const val POLL = "poll"

        /** The latest record, written again as the first of a session that just opened. */
        const val SESSION_START = "session_start"

        val all: Set<String> = setOf(
            START_REQUESTED, STALL_WATCH_STARTED, FLOW_STARTED, RESULT, FLOW_FAILED, STOPPED, LIFECYCLE_STOP,
            LIFECYCLE_START, OUTPUT, POLL, SESSION_START,
        )
    }

    /** Why ordinary prompts are held: the stop that began the hold. */
    object HoldCauses {
        const val CAMERA_OBSTRUCTED = "camera_obstructed"
        const val TOO_DARK = "too_dark"
        const val STARTUP_STALLED = "startup_stalled"
        const val PERCEPTION_STALLED = "perception_stalled"
        const val LIFECYCLE = "lifecycle"

        val all: Set<String> = setOf(CAMERA_OBSTRUCTED, TOO_DARK, STARTUP_STALLED, PERCEPTION_STALLED, LIFECYCLE)
    }

    /** The qualification levels, as frames write them. */
    val JUDGEMENTS: Set<String> = setOf("qualified", "degraded", "unqualified")
}

data class SessionTraceSummary(
    val sessionId: String,
    val startedAt: Long,
    val completedAt: Long,
    val totalFrames: Int,
    val droppedFrames: Int,
    val totalEvents: Int,
    val blockedFrames: Int,
    val dangerousFrames: Int,
    val avgProcessFrameMs: Double,
    val avgTotalPipelineMs: Double,
    val avgInferenceMs: Double,
    val p95TotalPipelineMs: Long,
    val maxTotalPipelineMs: Long,
    /** When the trace session closed, elapsed-realtime milliseconds; set by the trace service. Null in older traces. */
    val closedElapsedMs: Long? = null,
    /**
     * How many [GuidanceAlertTrace] records this session queued: the highest `seq` a complete trace holds.
     * Set by the trace service; null in older traces.
     */
    val guidanceAlertCount: Long? = null,
    /**
     * How many [SafetyStateTrace] records this session queued, the one written when it opened included: the highest
     * `seq` a complete trace holds. Set by the trace service; null in older traces.
     */
    val safetyStateCount: Long? = null,
)

class SessionTraceAccumulator(
    private val sessionId: String,
    private val startedAt: Long,
) {
    private val totalPipelineHistogram = IntArray(MAX_PIPELINE_MS_BUCKET + 1)
    private var totalFrames = 0
    private var droppedFrames = 0
    private var totalEvents = 0
    private var blockedFrames = 0
    private var dangerousFrames = 0
    private var totalProcessFrameMs = 0L
    private var totalInferenceMs = 0L
    private var totalPipelineMs = 0L
    private var maxTotalPipelineMs = 0L

    fun record(frameTrace: FrameTrace) {
        totalFrames++
        droppedFrames += frameTrace.droppedFramesSinceLast
        totalEvents += frameTrace.eventCount
        if (frameTrace.isBlocked) blockedFrames++
        if (frameTrace.isRoadDangerous) dangerousFrames++

        totalProcessFrameMs += frameTrace.processFrameMs
        totalInferenceMs += frameTrace.inferenceMs
        totalPipelineMs += frameTrace.totalPipelineMs
        maxTotalPipelineMs = maxOf(maxTotalPipelineMs, frameTrace.totalPipelineMs)
        val bucket = frameTrace.totalPipelineMs
            .coerceIn(0L, MAX_PIPELINE_MS_BUCKET.toLong())
            .toInt()
        totalPipelineHistogram[bucket]++
    }

    fun build(completedAt: Long): SessionTraceSummary {
        return SessionTraceSummary(
            sessionId = sessionId,
            startedAt = startedAt,
            completedAt = completedAt,
            totalFrames = totalFrames,
            droppedFrames = droppedFrames,
            totalEvents = totalEvents,
            blockedFrames = blockedFrames,
            dangerousFrames = dangerousFrames,
            avgProcessFrameMs = if (totalFrames > 0) totalProcessFrameMs.toDouble() / totalFrames else 0.0,
            avgTotalPipelineMs = if (totalFrames > 0) totalPipelineMs.toDouble() / totalFrames else 0.0,
            avgInferenceMs = if (totalFrames > 0) totalInferenceMs.toDouble() / totalFrames else 0.0,
            p95TotalPipelineMs = percentileTotalPipelineMs(0.95),
            maxTotalPipelineMs = maxTotalPipelineMs,
        )
    }

    private fun percentileTotalPipelineMs(percentile: Double): Long {
        if (totalFrames == 0) return 0L
        val target = ceil(totalFrames * percentile).toInt().coerceIn(1, totalFrames)
        var seen = 0
        for (pipelineMs in totalPipelineHistogram.indices) {
            seen += totalPipelineHistogram[pipelineMs]
            if (seen >= target) return pipelineMs.toLong()
        }
        return MAX_PIPELINE_MS_BUCKET.toLong()
    }

    private companion object {
        const val MAX_PIPELINE_MS_BUCKET = 10_000
    }
}
