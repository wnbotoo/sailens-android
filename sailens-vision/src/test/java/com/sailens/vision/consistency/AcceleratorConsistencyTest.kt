package com.sailens.vision.consistency

import com.sailens.core.geometry.NormalizedRect
import com.sailens.vision.detection.Detection
import com.sailens.vision.semantic.SemanticClassMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcceleratorConsistencyTest {

    private fun map(vararg ids: Int, width: Int = ids.size) = SemanticClassMap(width, ids.size / width, ids)

    private fun det(classId: Int, confidence: Float, x: Float = 0.1f) =
        Detection(classId, "c$classId", confidence, NormalizedRect(x, 0.2f, 0.3f, 0.4f))

    @Test
    fun `identical class maps agree fully`() {
        val a = compareClassMaps(map(0, 1, 2, 2), map(0, 1, 2, 2))
        assertEquals(1f, a.agreement, 0f)
        assertEquals(emptyList<ClassConfusion>(), a.topConfusions)
    }

    @Test
    fun `a replaced region is named as the largest confusion`() {
        // Sidewalk (1) read as building (2) on three of eight pixels, road (0) as sidewalk on one.
        val a = compareClassMaps(map(1, 1, 1, 1, 0, 0, 0, 0), map(2, 2, 2, 1, 1, 0, 0, 0))
        assertEquals(4f / 8, a.agreement, 1e-6f)
        assertEquals(ClassConfusion(1, 2, 3f / 8), a.topConfusions[0])
        assertEquals(ClassConfusion(0, 1, 1f / 8), a.topConfusions[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maps of different size are refused`() {
        compareClassMaps(map(0, 1, width = 2), map(0, 1, 2, 3, width = 2))
    }

    @Test
    fun `semantic verdict passes only when the mean and every frame agree`() {
        fun frame(agreement: Float) = ClassMapAgreement(agreement, 100, emptyList())
        assertEquals(ConsistencyVerdict.PASS, judgeSemantic(listOf(frame(0.98f), frame(0.96f))).verdict)
        // Mean 0.955, but one frame below 0.90: not a pass, and not low enough to fail.
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, judgeSemantic(listOf(frame(1f), frame(1f), frame(0.865f))).verdict)
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, judgeSemantic(emptyList()).verdict)
    }

    @Test
    fun `a shaken phone is inconclusive, a replaced region fails`() {
        fun frames(vararg a: Float) = a.map { ClassMapAgreement(it, 100, emptyList()) }
        // Measured on SM8850: the correct v0.6.6 export with the phone shaking...
        assertEquals(
            ConsistencyVerdict.INCONCLUSIVE,
            judgeSemantic(frames(0.867f, 0.956f, 0.861f, 0.865f, 0.967f, 0.964f, 0.96f, 0.96f)).verdict,
        )
        // ...and the broken old export.
        assertEquals(
            ConsistencyVerdict.FAIL,
            judgeSemantic(frames(0.307f, 0.331f, 0.334f, 0.321f, 0.364f, 0.337f, 0.312f, 0.372f)).verdict,
        )
    }

    @Test
    fun `jitter that straddles the threshold still matches`() {
        val a = compareDetections(listOf(det(56, 0.41f)), listOf(det(56, 0.36f)), threshold = 0.4f)
        assertEquals(DetectionAgreement(referenceCount = 1, matchedReference = 1, candidateCount = 0, extraCandidate = 0), a)
        assertEquals(ConsistencyVerdict.PASS, judgeDetection(listOf(a)).verdict)
    }

    @Test
    fun `a detection the accelerator pushes far below the threshold is lost, not jitter`() {
        // Production threshold 0.4: the CPU finds the chair, the accelerator's 0.26 drops it.
        val a = compareDetections(listOf(det(56, 0.90f)), listOf(det(56, 0.26f)), threshold = 0.4f)
        assertEquals(DetectionAgreement(referenceCount = 1, matchedReference = 0, candidateCount = 0, extraCandidate = 0), a)
        assertEquals(ConsistencyVerdict.FAIL, judgeDetection(listOf(a)).verdict)
    }

    @Test
    fun `a detection only the accelerator is sure of is extra, not jitter`() {
        val a = compareDetections(listOf(det(56, 0.26f)), listOf(det(56, 0.90f)), threshold = 0.4f)
        assertEquals(DetectionAgreement(referenceCount = 0, matchedReference = 0, candidateCount = 1, extraCandidate = 1), a)
        assertEquals(ConsistencyVerdict.FAIL, judgeDetection(listOf(a)).verdict)
    }

    @Test
    fun `a missed object is unmatched and a different class does not match`() {
        val a = compareDetections(listOf(det(56, 0.92f)), listOf(det(57, 0.9f)), threshold = 0.5f)
        assertEquals(DetectionAgreement(referenceCount = 1, matchedReference = 0, candidateCount = 1, extraCandidate = 1), a)
    }

    @Test
    fun `boxes that barely overlap do not match`() {
        val a = compareDetections(listOf(det(0, 0.9f, x = 0.1f)), listOf(det(0, 0.9f, x = 0.35f)), threshold = 0.5f)
        assertEquals(0, a.matchedReference)
    }

    @Test
    fun `each detection is used once`() {
        val a = compareDetections(listOf(det(0, 0.9f), det(0, 0.9f)), listOf(det(0, 0.9f)), threshold = 0.5f)
        assertEquals(DetectionAgreement(referenceCount = 2, matchedReference = 1, candidateCount = 1, extraCandidate = 0), a)
    }

    @Test
    fun `objects beyond the first ten are compared too`() {
        // Twelve objects side by side; the accelerator misses the eleventh and twelfth.
        fun objects(n: Int) = List(n) { i -> Detection(56, "chair", 0.9f - i * 0.01f, NormalizedRect(i * 0.08f, 0.2f, 0.07f, 0.4f)) }
        val a = compareDetections(objects(12), objects(10), threshold = 0.4f)
        assertEquals(DetectionAgreement(referenceCount = 12, matchedReference = 10, candidateCount = 10, extraCandidate = 0), a)
        assertEquals(ConsistencyVerdict.FAIL, judgeDetection(listOf(a)).verdict) // 10/12 < 0.9
    }

    @Test
    fun `detection verdict is pooled over frames`() {
        // The broken GPU export: the CPU sees the chair on every frame, the GPU never.
        val blind = List(8) { DetectionAgreement(1, 0, 0, 0) }
        assertEquals(ConsistencyVerdict.FAIL, judgeDetection(blind).verdict)
        assertEquals(0f, judgeDetection(blind).recall!!, 0f)

        val good = List(8) { DetectionAgreement(2, 2, 2, 0) } + DetectionAgreement(1, 0, 0, 0)
        assertEquals(ConsistencyVerdict.PASS, judgeDetection(good).verdict) // 16/17 > 0.9

        val nothing = List(8) { DetectionAgreement(0, 0, 0, 0) }
        assertEquals(ConsistencyVerdict.INCONCLUSIVE, judgeDetection(nothing).verdict)
        assertNull(judgeDetection(nothing).recall)
    }

    @Test
    fun `detections the reference does not make are a failure`() {
        val phantom = List(8) { DetectionAgreement(0, 0, 1, 1) }
        assertEquals(ConsistencyVerdict.FAIL, judgeDetection(phantom).verdict)
    }
}
