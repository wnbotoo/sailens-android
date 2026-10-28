package com.sailens.guidance.evaluation

import com.sailens.guidance.evaluation.SpeakerModel.Offer
import com.sailens.guidance.evaluation.SpeakerModel.Queued
import com.sailens.guidance.trace.capture.RegressionUtteranceRecord
import com.sailens.guidance.trace.capture.UtteranceRecordEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** det-first plan D1.4: the speaker model follows SpeechManager's arbitration. */
class SpeakerModelTest {

    private val model = SpeakerModel()
    private fun byId(inputs: List<SpeakerModel.Input>) = model.run(inputs).associateBy { it.id }

    @Test
    fun `only a strictly higher priority interrupts, and the interrupted one does not complete`() {
        val p = byId(
            listOf(
                Offer(0, "high", priority = 2, expiresAtMs = 3_000, durationMs = 1_500),
                Offer(500, "same", priority = 2, expiresAtMs = 3_500, durationMs = 1_500),
                Offer(700, "critical", priority = 3, expiresAtMs = 3_700, durationMs = 1_500),
            ),
        )
        assertEquals("outranked", p.getValue("same").reason)
        assertFalse(p.getValue("high").completed)
        assertEquals(700L, p.getValue("high").endAtMs)
        assertTrue(p.getValue("critical").completed)
        assertEquals(2_200L, p.getValue("critical").endAtMs)
    }

    @Test
    fun `speech past its deadline no longer outranks, and an expired offer is refused`() {
        val p = byId(
            listOf(
                Offer(0, "long", priority = 3, expiresAtMs = 1_000, durationMs = 5_000),
                Offer(1_200, "after", priority = 1, expiresAtMs = 4_000, durationMs = 1_000),
                Offer(5_000, "late", priority = 3, expiresAtMs = 4_000, durationMs = 1_000),
            ),
        )
        assertTrue(p.getValue("after").accepted)
        assertEquals("expired", p.getValue("late").reason)
    }

    @Test
    fun `notices queue behind, a prioritized one holds off guidance, and an unready engine refuses`() {
        val p = byId(
            listOf(
                Offer(0, "guidance", priority = 2, expiresAtMs = 3_000, durationMs = 1_000),
                Queued(100, "notice", priority = Int.MAX_VALUE, durationMs = 2_000),
                Offer(1_500, "blocked-by-notice", priority = 3, expiresAtMs = 4_500, durationMs = 1_000),
                SpeakerModel.EngineReady(4_000, ready = false),
                Offer(4_100, "not-ready", priority = 3, expiresAtMs = 7_000, durationMs = 1_000),
            ),
        )
        assertEquals(1_000L, p.getValue("notice").startAtMs)
        assertEquals("outranked", p.getValue("blocked-by-notice").reason)
        assertEquals("engine_not_ready", p.getValue("not-ready").reason)
    }

    @Test
    fun `calibration counts agreement and CRITICAL misjudgements`() {
        val predictions = model.run(
            listOf(
                Offer(0, "a", 3, 3_000, 1_000),
                Offer(100, "b", 3, 3_100, 1_000),
            ),
        )
        val c = SpeakerModel.Calibration(
            predictions,
            mapOf(
                "a" to SpeakerModel.Actual(accepted = true, startedAtMs = 30.0, doneAtMs = 1_100.0, critical = true),
                "b" to SpeakerModel.Actual(accepted = true, critical = true),
            ),
        )
        assertEquals(1, c.acceptedBoth)
        assertEquals(1, c.refusedPredictedOnly)
        assertEquals(1, c.criticalMisjudged)
        assertTrue(c.render("test").contains("DESCRIPTIVE ONLY"))
    }

    @Test
    fun `calibration counts completion, the direction of CRITICAL errors and the refusing side's reason, and pools`() {
        val predictions = model.run(
            listOf(
                Offer(0, "a", 3, 3_000, 1_000),
                Offer(100, "b", 3, 3_100, 1_000),
                Offer(2_000, "c", 2, 5_000, 1_000),
            ),
        )
        val c = SpeakerModel.Calibration(
            predictions,
            mapOf(
                // The model completes "a"; the device refused it and took "b" instead.
                "a" to SpeakerModel.Actual(accepted = false, critical = true, refusal = "outranked"),
                "b" to SpeakerModel.Actual(accepted = true, startedAtMs = 100.0, doneAtMs = 1_100.0, critical = true),
                "c" to SpeakerModel.Actual(accepted = true, startedAtMs = 2_000.0, doneAtMs = 3_000.0),
            ),
            durationSources = mapOf("measured" to 3),
        )
        assertEquals(1, c.completedBoth)
        assertEquals(1, c.completedPredictedOnly)
        assertEquals(1, c.completedActualOnly)
        assertEquals(2, c.criticalOffers)
        assertEquals(1, c.criticalAcceptOverclaimed)
        assertEquals(1, c.criticalCompletionOverclaimed)
        assertEquals(mapOf("device refused(outranked)" to 1, "model refused(outranked)" to 1), c.disagreements)

        val pooled = c + c
        assertEquals(2 * c.n, pooled.n)
        assertEquals(2, pooled.criticalAcceptOverclaimed)
        assertEquals(mapOf("measured" to 6), pooled.durationSources)
    }

    @Test
    fun `the D1_7 table tells the NEAR wording apart`() {
        assertEquals(1_193L, TtsDurations.zhCnRate130("event_obstacle_center_vehicle", near = false))
        assertEquals(1_952L, TtsDurations.zhCnRate130("event_obstacle_center_vehicle", near = true))
        assertEquals(null, TtsDurations.zhCnRate130("event_blocked", near = true))
    }

    @Test
    fun `durations come from other captures, then the D1_7 table, then the default`() {
        val wording = "event_obstacle_center_vehicle" to true
        val pooled = SpeakerDurations.pooled(listOf(wording to 2_100L, wording to 1_900L, wording to 2_000L), "other captures")
        assertEquals("other captures" to 2_000L, pooled.of(wording))
        // D1.7 has the NEAR wording at 1 952 ms: the device measured 48 ms longer, which carries over.
        assertEquals(48L, pooled.d17OffsetMs)
        assertEquals("d1.7 table" to 1_193L + 48, pooled.of("event_obstacle_center_vehicle" to false))
        assertEquals("default" to SpeakerModel.DEFAULT_DURATION_MS, pooled.of("event_blocked" to true))
        assertEquals("d1.7 table" to 1_193L, SpeakerDurations.D17_ONLY.of("event_obstacle_center_vehicle" to false))
        assertEquals(45L, SpeakerDurations.FROZEN_C.d17OffsetMs)
    }

    @Test
    fun `an excluded offer is run but not scored, and is counted by exclusion`() {
        val predictions = model.run(listOf(Offer(0, "a", 3, 3_000, 1_000), Offer(100, "b", 3, 3_100, 1_000)))
        val actual = mapOf(
            "a" to SpeakerModel.Actual(accepted = true, startedAtMs = 0.0, doneAtMs = 1_000.0),
            "b" to SpeakerModel.Actual(accepted = true),
        )
        val c = SpeakerModel.Calibration(predictions, actual, excluded = mapOf("b" to "x3 engine failure window"))
        assertEquals(1, c.n)
        assertEquals(0, c.refusedPredictedOnly)
        assertEquals(mapOf("x3 engine failure window" to 1), c.excluded)
        assertEquals(mapOf("x3 engine failure window" to 2), (c + c).excluded)
    }

    @Test
    fun `an engine failure window runs from the first sign of failure to a later generation's first start`() {
        fun r(event: String, atMs: Long, generation: Long?, reason: String? = null) =
            RegressionUtteranceRecord(event, attemptId = "x$atMs", logicalId = "x$atMs", engineGeneration = generation, elapsedRealtimeNanos = atMs * 1_000_000, reason = reason)
        val windows = PromptPolicyCaptureEvaluation.engineFailureWindows(
            listOf(
                r(UtteranceRecordEvents.STARTED, 100, 1),
                r(UtteranceRecordEvents.ERROR, 1_000, 1),
                r(UtteranceRecordEvents.REFUSED, 1_200, null, "engine_not_ready"),
                // The failed generation starting again does not end it.
                r(UtteranceRecordEvents.STARTED, 1_500, 1),
                r(UtteranceRecordEvents.STARTED, 2_000, 2),
                r(UtteranceRecordEvents.REFUSED, 3_000, null, "engine_not_ready"),
            ),
        )
        assertEquals(listOf(1_000L until 2_000L, 3_000L..Long.MAX_VALUE), windows)
        // speak() returning ERROR opens one too.
        val speakError = PromptPolicyCaptureEvaluation.engineFailureWindows(
            listOf(r(UtteranceRecordEvents.REFUSED, 1_000, 1, "speak_error"), r(UtteranceRecordEvents.STARTED, 2_000, 2)),
        )
        assertEquals(listOf(1_000L until 2_000L), speakError)
    }

    @Test
    fun `an exclusion runs on until neither speaker holds anything`() {
        // The device's notice ends at 1 990 ms, the model's (its recorded duration) at 2 000 ms: the
        // offer at 1 995 ms still rests on the notice's duration, so it is excluded too.
        val inputs = PromptPolicyCaptureEvaluation.SpeakerInputs(
            offers = listOf(
                PromptPolicyCaptureEvaluation.OfferInput(1_995, "o1", 3, 4_000, "event_obstacle_center_person" to false),
                PromptPolicyCaptureEvaluation.OfferInput(6_000, "o2", 3, 9_000, "event_obstacle_center_person" to false),
            ),
            queued = listOf(Queued(0, "notice", priority = null, durationMs = 2_000)),
            actual = mapOf(
                "o1" to SpeakerModel.Actual(accepted = true),
                "o2" to SpeakerModel.Actual(accepted = true),
            ),
            excluded = emptyMap(),
            windows = listOf(0L..1_990L to "x5 notice or description speaking"),
            deviceBusy = listOf(0L..1_990L),
            measured = emptyList(),
            label = "test",
        )
        val c = inputs.calibrate(SpeakerDurations.FROZEN_C)
        assertEquals(mapOf("x5 notice or description speaking" to 1), c.excluded)
        assertEquals(1, c.n)
        assertEquals(2_000L, PromptPolicyCaptureEvaluation.idleFrom(1_990, listOf(0L..1_990L, 0L..2_000L)))
        assertEquals(3_500L, PromptPolicyCaptureEvaluation.idleFrom(1_000, listOf(0L..2_000L, 1_900L..3_500L)))
        assertEquals(5_000L, PromptPolicyCaptureEvaluation.idleFrom(5_000, listOf(0L..2_000L)))
    }

    @Test
    fun `the D1_8 sets are fixed by session id and do not overlap`() {
        assertEquals(14, D18Sets.CALIBRATION.size)
        assertEquals(13, D18Sets.SEALED.size)
        assertTrue(D18Sets.CALIBRATION.intersect(D18Sets.SEALED).isEmpty())
        assertEquals(D18Sets.Group.SEALED, D18Sets.of("66adf21f-64bf-4a42-9948-5dab8b715902"))
        assertEquals(null, D18Sets.of("00000000-0000-0000-0000-000000000000"))
    }
}
