package com.sailens.vision.consistency

import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.ResizePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthConsistencyTest {

    private val transform = DepthInputTransform.create(540, 960, 224, 392, ResizePolicy.PAD_KEEP_ASPECT)
    private fun map(f: (Int) -> Float) = DisparityMap(224, 392, FloatArray(224 * 392, f), transform)
    private val reference = map { i -> 1f + (i % 224) / 224f }

    @Test
    fun `identical maps agree exactly and a scaled one by its scale`() {
        assertEquals(0f, compareDisparity(reference, reference).medianRelativeError, 0f)
        val scaled = map { i -> 1.01f * (1f + (i % 224) / 224f) }
        assertEquals(0.01f, compareDisparity(reference, scaled).medianRelativeError, 1e-4f)
    }

    @Test
    fun `a region off by itself shows in its own cell, though the whole-map median hides it`() {
        val square = DepthInputTransform.create(16, 16, 16, 16, ResizePolicy.STRETCH)
        val flat = DisparityMap(16, 16, FloatArray(256) { 1f }, square)
        // The top-left quarter of the top-left quarter, doubled: 1/16 of the pixels.
        val block = DisparityMap(16, 16, FloatArray(256) { i -> if (i % 16 < 4 && i / 16 < 4) 2f else 1f }, square)
        assertEquals(0f, compareDisparity(flat, block).medianRelativeError, 0f)
        val cells = compareDisparityByRegion(flat, block, grid = 4)
        assertEquals(16, cells.size)
        assertEquals(1f, cells[0], 0f)
        for (i in 1 until 16) assertEquals("cell $i", 0f, cells[i], 0f)
    }

    @Test
    fun `regions cover only the content the model saw, and a NaN is infinitely off there too`() {
        val padded = map { i -> 1f + (i % 224) / 224f }
        val withNaN = map { i -> 1f + (i % 224) / 224f }.also { it.values[it.contentRows.first * 224 + it.contentColumns.first] = Float.NaN }
        val cells = compareDisparityByRegion(padded, withNaN, grid = 8)
        // The NaN sits in the first content pixel, so the first cell; one value moves no cell's median.
        assertEquals(0f, cells[0], 0f)
        assertEquals(64, cells.size)
        assertEquals(0f, cells.max(), 0f)
        // Padding is not content: values there change no cell. 540x960 into 224x392 pads the left and right columns.
        assertTrue(reference.contentColumns.first > 0)
        val paddingChanged = map { i -> if (i % 224 < reference.contentColumns.first) 99f else 1f + (i % 224) / 224f }
        assertEquals(0f, compareDisparityByRegion(reference, paddingChanged, grid = 8).max(), 0f)
    }

    @Test
    fun `a non-finite candidate is as far off as it gets`() {
        val broken = map { Float.NaN }
        assertEquals(Float.POSITIVE_INFINITY, compareDisparity(reference, broken).medianRelativeError)
    }

    @Test
    fun `identical outputs agree even where the reference is zero, and a value against zero is infinitely off`() {
        val zeros = map { 0f }
        assertEquals(0f, compareDisparity(zeros, map { 0f }).medianRelativeError, 0f)
        assertEquals(0f, compareDisparity(zeros, map { 0f }).p95RelativeError, 0f)
        assertEquals(Float.POSITIVE_INFINITY, compareDisparity(zeros, map { 0.1f }).medianRelativeError)
        assertEquals(ConsistencyVerdict.PASS, judgeDepth(List(8) { DepthFrameAgreement(compareDisparity(zeros, map { 0f }), agreeValid) }).verdict)
    }

    @Test
    fun `a NaN or infinite value on either side fails the check, however the other values agree`() {
        val perfect = frame(0f, agreeValid)
        assertEquals(ConsistencyVerdict.PASS, judgeDepth(List(8) { perfect }).verdict)
        // Such a frame is not fitted: without this rule seven agreeing fits out of eight would pass.
        val candidateNaN = DepthFrameAgreement(DisparityAgreement(0f, 0f, 1000), fit = null, candidateNonFinite = 1)
        val referenceInf = DepthFrameAgreement(DisparityAgreement(0f, 0f, 1000), fit = null, referenceNonFinite = 3)
        for (bad in listOf(candidateNaN, referenceInf)) {
            val result = judgeDepth(List(7) { perfect } + bad)
            assertEquals(ConsistencyVerdict.FAIL, result.verdict)
            assertEquals(1, result.nonFiniteOutputFrames)
        }
    }

    @Test
    fun `padding columns are not compared`() {
        // The padding holds anything; only the content decides.
        val columns = reference.contentColumns
        val candidate = map { i -> if (i % 224 in columns) 1f + (i % 224) / 224f else 1e6f }
        assertEquals(0f, compareDisparity(reference, candidate).medianRelativeError, 0f)
    }

    private fun frame(median: Float, fit: GroundFitAgreement?) =
        DepthFrameAgreement(DisparityAgreement(median, median, 1000), fit)

    private val agreeValid = GroundFitAgreement(validityAgrees = true, referenceValid = true, labelAgreement = 0.98f)
    private val disagree = GroundFitAgreement(validityAgrees = false, referenceValid = true, labelAgreement = null)

    @Test
    fun `eight close frames whose fits agree pass`() {
        val result = judgeDepth(List(8) { frame(0.005f, agreeValid) })
        assertEquals(ConsistencyVerdict.PASS, result.verdict)
        assertEquals(8, result.fitAgreeingFrames)
    }

    @Test
    fun `one frame far off fails`() {
        val frames = List(7) { frame(0.005f, agreeValid) } + frame(0.6f, agreeValid)
        assertEquals(ConsistencyVerdict.FAIL, judgeDepth(frames).verdict)
    }

    @Test
    fun `fits that disagree on most frames fail`() {
        val frames = List(4) { frame(0.005f, agreeValid) } + List(4) { frame(0.005f, disagree) }
        assertEquals(ConsistencyVerdict.FAIL, judgeDepth(frames).verdict)
    }

    @Test
    fun `too few frames with geometry cannot pass on the ones they kept`() {
        // Six frames with geometry that all agree: the denominator stays eight, so this is not a pass.
        val frames = List(6) { frame(0.005f, agreeValid) } + List(2) { frame(0.005f, null) }
        val result = judgeDepth(frames)
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, result.verdict)
        assertEquals(6, result.framesWithGeometry)
        // With no geometry at all the fit is not judged either, and that is not a failure.
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, judgeDepth(List(8) { frame(0.005f, null) }).verdict)
    }

    @Test
    fun `the band between pass and fail is inconclusive`() {
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, judgeDepth(List(8) { frame(0.05f, agreeValid) }).verdict)
        val lowLabels = GroundFitAgreement(validityAgrees = true, referenceValid = true, labelAgreement = 0.9f)
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, judgeDepth(List(8) { frame(0.005f, lowLabels) }).verdict)
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, judgeDepth(emptyList()).verdict)
    }
}
