package com.sailens.guidance.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceSetTest {
    private fun found(id: String?, unusable: String? = null) = AcceptanceSet.Found(id ?: "dir", id, unusable)

    private val calibration = D18Sets.CALIBRATION.sorted().map { found(it) }
    private val sealed = D18Sets.SEALED.sorted().map { found(it) }

    @Test
    fun `a whole set passes`() {
        assertEquals(emptyList<String>(), AcceptanceSet.problems(calibration))
        assertEquals(emptyList<String>(), AcceptanceSet.problems(sealed))
    }

    @Test
    fun `a missing capture is refused`() {
        val problems = AcceptanceSet.problems(sealed.drop(1))
        assertEquals(1, problems.size)
        assertTrue(problems.single(), problems.single().startsWith("sealed: 1 of 13 missing"))
    }

    @Test
    fun `a capture found twice is refused`() {
        assertTrue(AcceptanceSet.problems(sealed + sealed.first()).single().endsWith("found 2 times"))
    }

    @Test
    fun `a capture of neither set is refused`() {
        assertTrue(AcceptanceSet.problems(sealed + found("00000000-0000-0000-0000-000000000000")).single().endsWith("in neither frozen set"))
    }

    @Test
    fun `the two sets together are refused`() {
        assertEquals(listOf("calibration and sealed captures together"), AcceptanceSet.problems(sealed + calibration.first()))
    }

    @Test
    fun `an incomplete capture, or one that is no model-regression capture, is refused`() {
        val incomplete = sealed.mapIndexed { i, f -> if (i == 0) found(f.id, "not complete") else f }
        assertEquals(1, AcceptanceSet.problems(incomplete).size)
        assertEquals(1, AcceptanceSet.problems(sealed + found(null, "not a model-regression capture")).size)
    }

    @Test
    fun `nothing is refused`() {
        assertEquals(listOf("no capture of a frozen set"), AcceptanceSet.problems(emptyList()))
    }
}
