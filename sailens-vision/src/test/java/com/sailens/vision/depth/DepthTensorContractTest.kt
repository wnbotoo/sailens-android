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
    fun `a shape that reads two ways takes the reading whose input and output sizes match`() {
        // [1,3,4,3] is NCHW 3x4 or NHWC 4x3; the output says which.
        val nchw = compatible(listOf(1, 3, 4, 3), listOf(1, 4, 3))
        assertEquals(ImageTensorLayout.NCHW, nchw.inputLayout)
        assertEquals(3, nchw.inputWidth)
        assertEquals(4, nchw.inputHeight)
        val nhwc = compatible(listOf(1, 3, 4, 3), listOf(1, 3, 4))
        assertEquals(ImageTensorLayout.NHWC, nhwc.inputLayout)
        assertEquals(4, nhwc.inputWidth)
        assertEquals(3, nhwc.inputHeight)
        // [1,1,4,1] is a 4x1 or a 1x4 map; the input says which.
        val wide = compatible(listOf(1, 1, 4, 3), listOf(1, 1, 4, 1))
        assertEquals(ImageTensorLayout.NHWC, wide.inputLayout)
        assertEquals(4, wide.outputWidth)
        assertEquals(1, wide.outputHeight)
        val tall = compatible(listOf(1, 3, 4, 1), listOf(1, 1, 4, 1))
        assertEquals(ImageTensorLayout.NCHW, tall.inputLayout)
        assertEquals(1, tall.outputWidth)
        assertEquals(4, tall.outputHeight)
    }

    @Test
    fun `an input whose two layouts both fit is refused, and one that fits neither is a size mismatch`() {
        // [1,3,3,3]: channels first or last give the same 3x3, so the layout cannot be told.
        val ambiguous = DepthTensorContract.resolve(model(listOf(tensor(listOf(1, 3, 3, 3))), listOf(tensor(listOf(1, 3, 3), index = 1))))
        assertTrue(ambiguous.toString(), ambiguous is DepthTensorContract.Resolution.InputUnreadable)
        val neither = DepthTensorContract.resolve(model(listOf(tensor(listOf(1, 3, 4, 3))), listOf(tensor(listOf(1, 5, 5), index = 1))))
        assertTrue(neither.toString(), neither is DepthTensorContract.Resolution.OutputSizeMismatch)
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
