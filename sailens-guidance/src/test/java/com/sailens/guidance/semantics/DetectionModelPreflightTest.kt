package com.sailens.guidance.semantics

import com.sailens.runtime.TfliteModelMetadata
import com.sailens.runtime.TfliteTensorElementType
import com.sailens.runtime.TfliteTensorMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionModelPreflightTest {

    @Test
    fun `a raw attribute-major head with the declared class count passes`() {
        assertEquals(
            DetectionModelPreflight.Result.Compatible,
            DetectionModelPreflight.checkTensors(metadata(output = listOf(1, 84, 8400)), classCount = 80),
        )
    }

    @Test
    fun `an end-to-end head passes whatever the class count`() {
        assertEquals(
            DetectionModelPreflight.Result.Compatible,
            DetectionModelPreflight.checkTensors(metadata(output = listOf(1, 300, 6)), classCount = 80),
        )
    }

    @Test
    fun `a raw head with a different class count is unreadable`() {
        val result = DetectionModelPreflight.checkTensors(metadata(output = listOf(1, 24, 8400)), classCount = 80)

        assertTrue(result is DetectionModelPreflight.Result.OutputUnreadable)
    }

    @Test
    fun `a non-square input is unreadable, since the letterbox decoder assumes a square`() {
        val result = DetectionModelPreflight.checkTensors(
            metadata(input = listOf(1, 384, 640, 3), output = listOf(1, 84, 8400)),
            classCount = 80,
        )

        assertTrue(result is DetectionModelPreflight.Result.OutputUnreadable)
    }

    private fun metadata(input: List<Int> = listOf(1, 640, 640, 3), output: List<Int>) = TfliteModelMetadata(
        inputs = listOf(tensor("images", input)),
        outputs = listOf(tensor("output0", output)),
        signatures = emptyList(),
    )

    private fun tensor(name: String, shape: List<Int>) = TfliteTensorMetadata(
        index = 0,
        name = name,
        shape = shape,
        elementType = TfliteTensorElementType.FLOAT32,
        quantization = null,
    )
}
