package com.sailens.vision.consistency

import com.sailens.vision.depth.DepthInputTransform
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.ResizePolicy
import org.junit.Assert.assertEquals
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
    fun `a non-finite candidate is as far off as it gets`() {
        val broken = map { Float.NaN }
        assertEquals(Float.POSITIVE_INFINITY, compareDisparity(reference, broken).medianRelativeError)
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
