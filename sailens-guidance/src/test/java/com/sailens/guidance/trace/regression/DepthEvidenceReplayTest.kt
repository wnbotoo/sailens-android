package com.sailens.guidance.trace.regression

import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundFitValidity
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.guidance.geometry.IntrinsicsSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.model.trace.DepthRunTrace
import com.sailens.guidance.sim.Pose
import com.sailens.guidance.sim.Scenarios
import com.sailens.guidance.sim.SyntheticCamera
import com.sailens.guidance.sim.renderDisparity
import com.sailens.guidance.trace.capture.DepthBlobRef
import com.sailens.guidance.trace.capture.DepthEvidenceRecord
import com.sailens.guidance.trace.capture.DepthFrameRecord
import com.sailens.guidance.trace.capture.DepthOutputRecord
import com.sailens.guidance.trace.capture.DepthPlaneRecord
import com.sailens.guidance.trace.capture.toRecord
import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.ResizePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The rules of the depth evidence replay (docs/field-capture.md §11) on a recorded walk toward a drop, where the
 * fitter's history decides later fits: exact replay, acceptance only up to the first gap, and the PC comparison
 * with its bands. The records are made by a live fitter the way the lane makes them.
 */
class DepthEvidenceReplayTest {

    private val camera = SyntheticCamera()
    private val aw = 720
    private val ah = 1280
    private val mw = 126
    private val mh = 224
    private val gridW = camera.mapWidth
    private val gridH = camera.mapHeight
    private val scale = aw.toFloat() / camera.mapWidth
    private val intrinsics = CameraIntrinsics(camera.focal * scale, camera.focal * scale, camera.cx * scale, camera.cy * scale, aw, ah, IntrinsicsSource.CALIBRATION)
    private val transform = DepthInputTransform.create(aw, ah, mw, mh, ResizePolicy.PAD_KEEP_ASPECT)
    private val drop = Scenarios.curbAndDrop.frames[40].world

    private fun gravity(pose: Pose): Vec3 {
        val t = Math.toRadians(pose.pitchDownDeg.toDouble())
        return Vec3(0f, cos(t).toFloat(), sin(t).toFloat())
    }

    /** A recorded session: [fits] runs walking toward the drop, as the lane records them, and their outputs. */
    private class Recorded(val records: List<DepthEvidenceRecord>, val outputs: Map<Long, FloatArray>) {
        fun output(record: DepthEvidenceRecord): FloatArray = outputs.getValue(record.runId)
    }

    private fun walkPose(i: Int) = Pose(1.3f, 25f, walkedM = i * 0.08f)

    private fun record(fits: Int, generation: Long = 1): Recorded = record(
        List(fits) { i ->
            renderDisparity(camera, walkPose(i), drop, mw, mh, aw, ah, { x, y -> transform.toAnalysisX(x) to transform.toAnalysisY(y) })
        },
        generation,
    ) { walkPose(it) }

    /** Records [outputs] in order through one live fitter, the way the lane records them. */
    private fun record(outputs: List<FloatArray>, generation: Long = 1, poseOf: (Int) -> Pose): Recorded {
        val fitter = GroundFitter()
        val records = mutableListOf<DepthEvidenceRecord>()
        val byRun = mutableMapOf<Long, FloatArray>()
        val noBlob = DepthBlobRef(0, 0, 0)
        val plane = DepthPlaneRecord(1, 1, 1, 1, noBlob)
        for ((i, values) in outputs.withIndex()) {
            val pose = poseOf(i)
            val map = DisparityMap(mw, mh, values, transform)
            val geometry = CameraGeometry(
                frameTimeNanos = i * 333_000_000L,
                timeBasis = TimeBasis.CAMERA_REALTIME,
                intrinsics = intrinsics,
                gravityInCamera = gravity(pose),
                gravityAgeNanos = 4_000_000,
                phone = PhoneGeometrySettings.DEFAULT,
                quality = GeometryQuality.Ok,
            )
            val observation = fitter.fit(map, intrinsics, gravity(pose), gridW, gridH, geometry.frameTimeNanos)
            val runId = i + 1L
            byRun[runId] = values
            records += DepthEvidenceRecord(
                generation = generation,
                runId = runId,
                fitOrdinal = i.toLong(),
                outcome = DepthRunTrace.PUBLISHED,
                frameSequence = 10L * runId,
                sensorTimestampNanos = geometry.frameTimeNanos!!,
                frame = DepthFrameRecord(1, 1, 90, plane, plane, plane),
                geometry = geometry.toRecord(),
                transform = transform.toRecord(),
                gridWidth = gridW,
                gridHeight = gridH,
                output = DepthOutputRecord(mw, mh, noBlob),
                observation = observation.toRecord(),
                modelShape = "1x3x${mh}x$mw>1x${mh}x$mw",
            )
        }
        return Recorded(records, byRun)
    }

    private val standing = Pose(1.3f, 25f)

    /**
     * Level ground, disparity `10·q + 0.2`, with a patch near the feet (about 2.7 % of the view) whose disparity is
     * [depression] lower. The fit labels a cell below ground past 12 % (the inlier band times the structure factor),
     * and a patch that large arms the support-switch guard: 11.8 % and 12.2 % differ by 0.45 % of disparity and fall
     * on either side.
     */
    private fun patchedGround(depression: Float): FloatArray =
        ground { u, v -> if (u >= 0.42f * aw && u < 0.58f * aw && nearRows(v)) depression else 0f }

    private fun nearRows(v: Float) = v >= 0.78f * ah && v < 0.95f * ah

    /** Level ground, its disparity lowered by `depressionAt(u, v)` (a fraction) at analysis pixel (u, v). */
    private fun ground(depressionAt: (Float, Float) -> Float): FloatArray {
        val g = gravity(standing)
        return FloatArray(mw * mh) { i ->
            val u = transform.toAnalysisX(i % mw + 0.5f)
            val v = transform.toAnalysisY(i / mw + 0.5f)
            val q = g.x * (u - intrinsics.cx) / intrinsics.fx + g.y * (v - intrinsics.cy) / intrinsics.fy + g.z
            10f * q * (1f - depressionAt(u, v)) + 0.2f
        }
    }

    /**
     * Two patches near the feet, each large enough to arm the guard, overlapping by less than half: A spans 40–56 %
     * of the width, B 50–66 %. In [below] the depression passes the 12 % band (below ground), in the rest of A and B
     * it stays short of it.
     */
    private fun twoPatches(below: (inA: Boolean, inB: Boolean) -> Boolean): FloatArray = ground { u, v ->
        val inA = nearRows(v) && u >= 0.40f * aw && u < 0.56f * aw
        val inB = nearRows(v) && u >= 0.50f * aw && u < 0.66f * aw
        when {
            !inA && !inB -> 0f
            below(inA, inB) -> 0.122f
            else -> 0.118f
        }
    }

    private val few = DepthEvidenceThresholds(minAcceptableRuns = 20)

    @Test
    fun `a whole session replays exactly, its later fits standing on the history`() {
        val session = record(50)
        assertTrue(
            "the walk reaches the hold that only history explains",
            session.records.any { it.observation?.validity == GroundFitValidity.SUPPORT_SWITCH.name.lowercase() },
        )
        val report = DepthEvidenceReplay(few).replay(session.records, session::output).single()
        assertEquals(50, report.acceptableRuns)
        assertEquals(50, report.exactRuns)
        assertNull(report.firstGap)
        assertEquals(DepthEvidenceVerdict.CANNOT_JUDGE, report.verdict)
        assertTrue(report.verdictReason, "replays exactly" in report.verdictReason)
    }

    @Test
    fun `nothing after the first gap counts, however many runs follow`() {
        val session = record(50)
        val gapped = session.records.filter { it.fitOrdinal != 30L }
        val report = DepthEvidenceReplay(few).replay(gapped, session::output).single()
        assertEquals(30, report.acceptableRuns)
        assertEquals(49, report.fittedRecords)
        assertTrue(report.firstGap!!, "fit call 30 is missing" in report.firstGap!!)
    }

    @Test
    fun `a run the stream dropped ends acceptance there, though it made no fit`() {
        val session = record(40)
        // Run 21 is fit call 20; a dropped run 21 of another depth session changes nothing here.
        val report = DepthEvidenceReplay(few).replay(session.records, session::output, firstDropped = DepthEvidenceReplay.DroppedRun(1, 21)).single()
        assertEquals(20, report.acceptableRuns)
        assertTrue(report.firstGap!!, "dropped run 21" in report.firstGap!!)
        val other = DepthEvidenceReplay(few).replay(session.records, session::output, firstDropped = DepthEvidenceReplay.DroppedRun(2, 21)).single()
        assertEquals(40, other.acceptableRuns)
    }

    @Test
    fun `a session whose first fit was not recorded has nothing acceptable and cannot pass`() {
        val session = record(40)
        val late = session.records.drop(1)
        val report = DepthEvidenceReplay(few).replay(late, session::output, candidate = session::output).single()
        assertEquals(0, report.acceptableRuns)
        assertEquals(DepthEvidenceVerdict.CANNOT_JUDGE, report.verdict)
    }

    @Test
    fun `runs without a fit leave no gap, and records may come in any order`() {
        val session = record(30)
        val nonFinite = session.records[5].copy(runId = 1000, fitOrdinal = null, outcome = DepthRunTrace.NON_FINITE_OUTPUT, observation = null)
        val report = DepthEvidenceReplay(few).replay((session.records + nonFinite).shuffled(java.util.Random(7)), session::output).single()
        assertEquals(30, report.acceptableRuns)
        assertEquals(1, report.nonFiniteRecords)
        assertEquals(30, report.exactRuns)
    }

    @Test
    fun `a replayed fit that differs from the phone's fails`() {
        val session = record(30)
        val tampered = session.records.mapIndexed { i, r ->
            if (i == 5) r.copy(observation = r.observation!!.copy(groundMaskCrc32 = r.observation!!.groundMaskCrc32 xor 1)) else r
        }
        val report = DepthEvidenceReplay(few).replay(tampered, session::output, candidate = session::output).single()
        assertEquals(DepthEvidenceVerdict.FAIL, report.verdict)
        assertEquals(1, report.mismatchCount)
        assertEquals("ground mask", report.mismatches.single().what)
        assertEquals(6L, report.mismatches.single().runId)
    }

    @Test
    fun `a PC within the bands passes`() {
        val session = record(30)
        val pc = { r: DepthEvidenceRecord -> session.output(r).let { v -> FloatArray(v.size) { v[it] * 1.002f } } }
        val report = DepthEvidenceReplay(few).replay(session.records, session::output, candidate = pc).single()
        assertEquals(report.verdictReason, DepthEvidenceVerdict.PASS, report.verdict)
        val agreement = report.pc!!
        assertEquals(30, agreement.runs)
        assertEquals(0.002f, agreement.medianRelativeError, 2e-4f)
        assertEquals(1f, agreement.fitAgreement, 0f)
        assertEquals(1f, agreement.labelAgreement!!, 0f)
    }

    @Test
    fun `a PC output off by more than the band fails, though the fits still agree`() {
        val session = record(30)
        // A uniform scale changes no label: only the tensor band can catch it.
        val pc = { r: DepthEvidenceRecord -> session.output(r).let { v -> FloatArray(v.size) { v[it] * 1.05f } } }
        val report = DepthEvidenceReplay(few).replay(session.records, session::output, candidate = pc).single()
        assertEquals(DepthEvidenceVerdict.FAIL, report.verdict)
        assertEquals(1f, report.pc!!.fitAgreement, 0f)
        assertTrue(report.verdictReason, "median relative error" in report.verdictReason)
    }

    @Test
    fun `one call that goes differently counts once, though in session order it keeps the two apart`() {
        // The first run's patch arms the guard on the PC and not on the phone; plain ground follows. In session order
        // the PC's fitter then switches and holds to the end, as nothing shows two levels again.
        val plain = patchedGround(0f)
        val phoneOutputs = listOf(patchedGround(0.118f)) + List(49) { plain }
        val pcOutputs = listOf(patchedGround(0.122f)) + List(49) { plain }
        val session = record(phoneOutputs) { standing }
        assertTrue(session.records.all { it.observation?.validity == GroundFitValidity.VALID.name.lowercase() })
        val report = DepthEvidenceReplay(few).replay(session.records, session::output, candidate = { pcOutputs[it.fitOrdinal!!.toInt()] }).single()
        val agreement = report.pc!!
        assertEquals(1f / 50f, agreement.sessionValidityAgreement, 1e-6f)
        assertEquals(49f / 50f, agreement.fitAgreement, 1e-6f)
        val only = agreement.disagreements.single()
        assertEquals(0L, only.fitOrdinal)
        assertEquals("guard", only.what)
        assertEquals("not holding, not armed", only.phone)
        assertEquals("not holding, armed", only.pc)
        assertEquals(report.verdictReason, DepthEvidenceVerdict.PASS, report.verdict)
    }

    @Test
    fun `a different history that shows only after fits that keep it counts against the run that left it`() {
        // Both sides arm the guard, on different cells: the phone's patch B, the PC's patch A. No-ground runs follow,
        // which keep the history they find. Then B alone stays below and the rest of A reads as ground: the phone's
        // history (B) sees no switch, the PC's (A) switches. Every third and every fifth run.
        val phonePatch = twoPatches { _, inB -> inB }
        val pcPatch = twoPatches { inA, _ -> inA }
        val target = ground { u, v -> if (nearRows(v) && u >= 0.50f * aw && u < 0.66f * aw) 0.122f else 0f }
        val noGround = FloatArray(mw * mh) { 1f }
        for (period in listOf(3, 5)) {
            val phoneOutputs = List(45) { when (it % period) { 0 -> phonePatch; period - 1 -> target; else -> noGround } }
            val pcOutputs = List(45) { when (it % period) { 0 -> pcPatch; period - 1 -> target; else -> noGround } }
            val session = record(phoneOutputs) { standing }
            assertTrue(session.records.none { it.observation?.validity == GroundFitValidity.SUPPORT_SWITCH.name.lowercase() })
            val report = DepthEvidenceReplay(few).replay(session.records, session::output, candidate = { pcOutputs[it.fitOrdinal!!.toInt()] }).single()
            assertEquals(45, report.exactRuns)
            val agreement = report.pc!!
            assertEquals("period $period", 45 - 45 / period, 45 - agreement.disagreementCount)
            assertTrue(agreement.disagreements.all { it.fitOrdinal % period == 0L && "from its history" in it.what })
            assertEquals("period $period", DepthEvidenceVerdict.FAIL, report.verdict)
            assertTrue(report.verdictReason, "fit agreement" in report.verdictReason)
        }
    }

    @Test
    fun `a difference that keeps recurring fails, though each run fitted on its own agrees and the tensors pass`() {
        // The patch comes back every other run: on the PC it arms the guard each time and the plain run after it
        // switches; on the phone it never arms. Fitted on their own, every run is valid on both sides.
        val plain = patchedGround(0f)
        val phoneOutputs = List(40) { if (it % 2 == 0) patchedGround(0.118f) else plain }
        val pcOutputs = List(40) { if (it % 2 == 0) patchedGround(0.122f) else plain }
        assertTrue(pcOutputs.indices.all { i -> fitAlone(phoneOutputs[i]).isValid && fitAlone(pcOutputs[i]).isValid })
        val session = record(phoneOutputs) { standing }
        val report = DepthEvidenceReplay(few).replay(session.records, session::output, candidate = { pcOutputs[it.fitOrdinal!!.toInt()] }).single()
        assertEquals(40, report.exactRuns)
        val agreement = report.pc!!
        assertEquals(0.5f, agreement.fitAgreement, 1e-6f)
        assertEquals(20, agreement.disagreementCount)
        assertEquals(DepthEvidenceVerdict.FAIL, report.verdict)
        assertTrue(report.verdictReason, "fit agreement" in report.verdictReason)
        assertTrue(report.verdictReason, "median relative error" !in report.verdictReason)
    }

    private fun fitAlone(values: FloatArray) =
        GroundFitter().fit(DisparityMap(mw, mh, values, transform), intrinsics, gravity(standing), gridW, gridH, 0L)

    @Test
    fun `runs whose PC output gives another fit fail the fit band, though the tensors pass`() {
        val session = record(30)
        // Five runs read as depth instead of disparity; the median over the runs stays on the 25 identical ones.
        val pc = { r: DepthEvidenceRecord -> session.output(r).let { v -> if (r.fitOrdinal!! < 5) FloatArray(v.size) { -v[it] } else v } }
        val report = DepthEvidenceReplay(few).replay(session.records, session::output, candidate = pc).single()
        assertEquals(DepthEvidenceVerdict.FAIL, report.verdict)
        assertEquals(25f / 30f, report.pc!!.fitAgreement, 1e-6f)
        assertTrue(report.verdictReason, "fit agreement" in report.verdictReason)
        assertTrue(report.verdictReason, "median relative error" !in report.verdictReason)
    }

    @Test
    fun `the comparison stops at the first run without a PC output`() {
        val session = record(30)
        val pc = { r: DepthEvidenceRecord -> if (r.fitOrdinal == 15L) null else session.output(r) }
        val report = DepthEvidenceReplay(few).replay(session.records, session::output, candidate = pc).single()
        assertEquals(15, report.pc!!.runs)
        assertTrue(report.pc!!.stoppedAt!!, "fit call 15" in report.pc!!.stoppedAt!!)
        assertEquals(DepthEvidenceVerdict.CANNOT_JUDGE, report.verdict)
    }

    @Test
    fun `a PC output missing for the last acceptable run cannot be judged, though the ones before it reach the minimum`() {
        // 21 acceptable runs, a floor of 20: the PC has the first 20, all agreeing, and not the 21st.
        val session = record(21)
        val pc = { r: DepthEvidenceRecord -> if (r.fitOrdinal == 20L) null else session.output(r) }
        val report = DepthEvidenceReplay(few).replay(session.records, session::output, candidate = pc).single()
        assertEquals(20, report.pc!!.runs)
        assertEquals(1f, report.pc!!.fitAgreement, 0f)
        assertEquals(DepthEvidenceVerdict.CANNOT_JUDGE, report.verdict)
        assertTrue(report.verdictReason, "PC outputs incomplete" in report.verdictReason)
        // With every output there, the same session passes.
        assertEquals(DepthEvidenceVerdict.PASS, DepthEvidenceReplay(few).replay(session.records, session::output, candidate = session::output).single().verdict)
    }

    @Test
    fun `fewer acceptable runs than the minimum cannot be judged, however well they agree`() {
        val session = record(50)
        val report = DepthEvidenceReplay().replay(session.records, session::output, candidate = session::output).single()
        assertEquals(DepthEvidenceVerdict.CANNOT_JUDGE, report.verdict)
        assertTrue(report.verdictReason, "fewer than 300" in report.verdictReason)
    }

    @Test
    fun `each depth session replays with its own fitter`() {
        // The same run ids and fit ordinals in both: one fitter for the two would see call 0 twice.
        val first = record(25, generation = 1)
        val second = record(25, generation = 2)
        val reports = DepthEvidenceReplay(few).replay(first.records + second.records, { r -> (if (r.generation == 1L) first else second).output(r) })
        assertEquals(listOf(1L, 2L), reports.map { it.generation })
        assertTrue(reports.all { it.acceptableRuns == 25 && it.exactRuns == 25 })
    }
}
