package com.sailens.vision.depth

import com.sailens.runtime.ImageTensorLayout
import com.sailens.runtime.TfliteModelMetadata
import com.sailens.runtime.TfliteTensorElementType
import com.sailens.runtime.TfliteTensorMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthTensorContractTest {

    private fun tensor(shape: List<Int>, type: TfliteTensorElementType = TfliteTensorElementType.FLOAT32, index: Int = 0) =
        TfliteTensorMetadata(index = index, name = "t$index", shape = shape, elementType = type, quantization = null)

    private fun model(inputs: List<TfliteTensorMetadata>, outputs: List<TfliteTensorMetadata>) =
        TfliteModelMetadata(inputs = inputs, outputs = outputs, signatures = emptyList())

    private fun compatible(input: List<Int>, output: List<Int>): DepthTensorSpec {
        val resolved = DepthTensorContract.resolve(model(listOf(tensor(input)), listOf(tensor(output, index = 1))))
        assertTrue("$input > $output: $resolved", resolved is DepthTensorContract.Resolution.Compatible)
        return (resolved as DepthTensorContract.Resolution.Compatible).spec
    }

    // One parser for every conforming model, with no branch per model: the reference model and a
    // different architecture resolve through the same code.

    @Test
    fun `the reference model's tensors resolve`() {
        val spec = compatible(listOf(1, 3, 392, 224), listOf(1, 392, 224))
        assertEquals(224, spec.inputWidth)
        assertEquals(392, spec.inputHeight)
        assertEquals(ImageTensorLayout.NCHW, spec.inputLayout)
        assertEquals(224, spec.outputWidth)
        assertEquals(392, spec.outputHeight)
        assertEquals("1x3x392x224>1x392x224", spec.shapeLabel)
    }

    @Test
    fun `a different architecture with a channel axis on its output resolves the same way`() {
        val spec = compatible(listOf(1, 3, 512, 288), listOf(1, 1, 512, 288))
        assertEquals(288, spec.inputWidth)
        assertEquals(512, spec.inputHeight)
        assertEquals(288, spec.outputWidth)
        assertEquals(512, spec.outputHeight)
    }

    @Test
    fun `channels-last tensors resolve`() {
        val spec = compatible(listOf(1, 392, 224, 3), listOf(1, 392, 224, 1))
        assertEquals(ImageTensorLayout.NHWC, spec.inputLayout)
        assertEquals(224, spec.outputWidth)
        assertEquals(392, spec.outputHeight)
    }

    @Test
    fun `an output of another size is refused, not remapped`() {
        val resolved = DepthTensorContract.resolve(model(listOf(tensor(listOf(1, 3, 224, 392))), listOf(tensor(listOf(1, 112, 112), index = 1))))
        assertTrue(resolved.toString(), resolved is DepthTensorContract.Resolution.OutputSizeMismatch)
    }

    @Test
    fun `tensors outside the contract are refused`() {
        val good = tensor(listOf(1, 3, 392, 224))
        val goodOut = tensor(listOf(1, 392, 224), index = 1)
        val inputs = listOf(
            listOf(tensor(listOf(1, 3, 392, 224), TfliteTensorElementType.UINT8)),
            listOf(tensor(listOf(1, 4, 392, 224))),
            listOf(tensor(listOf(1, 3, 0, 224))),
            listOf(good, tensor(listOf(1, 3, 392, 224), index = 2)),
            emptyList(),
        )
        for (i in inputs) {
            val resolved = DepthTensorContract.resolve(model(i, listOf(goodOut)))
            assertTrue("$i: $resolved", resolved is DepthTensorContract.Resolution.InputUnreadable)
        }
        val outputs = listOf(
            listOf(tensor(listOf(1, 392, 224), TfliteTensorElementType.INT8, index = 1)),
            listOf(tensor(listOf(1, 2, 392, 224), index = 1)),
            listOf(tensor(listOf(392, 224), index = 1)),
            listOf(goodOut, tensor(listOf(1, 392, 224), index = 2)),
        )
        for (o in outputs) {
            val resolved = DepthTensorContract.resolve(model(listOf(good), o))
            assertTrue("$o: $resolved", resolved is DepthTensorContract.Resolution.OutputUnreadable)
        }
    }

    @Test
    fun `the preflight reports what the contract finds`() {
        val ok = DepthModelPreflight.checkTensors(model(listOf(tensor(listOf(1, 3, 392, 224))), listOf(tensor(listOf(1, 392, 224), index = 1))))
        assertTrue(ok is DepthModelPreflight.Result.Compatible)
        val mismatch = DepthModelPreflight.checkTensors(model(listOf(tensor(listOf(1, 3, 224, 392))), listOf(tensor(listOf(1, 112, 112), index = 1))))
        assertTrue(mismatch is DepthModelPreflight.Result.OutputSizeMismatch)
    }
}
