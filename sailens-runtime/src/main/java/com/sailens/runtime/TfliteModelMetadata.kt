package com.sailens.runtime

import android.content.Context
import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TensorQuantization(
    val scale: Float,
    val zeroPoint: Int,
) {
    fun toInputQuantization(): ModelInputQuantization {
        return ModelInputQuantization(scale = scale, zeroPoint = zeroPoint)
    }

    fun dequantize(value: Byte): Float {
        return (value.toInt() - zeroPoint) * scale
    }
}

data class TfliteTensorMetadata(
    val index: Int,
    val name: String,
    val shape: List<Int>,
    val elementType: TfliteTensorElementType,
    val quantization: TensorQuantization?,
)

data class TfliteSignatureTensorMetadata(
    val name: String,
    val tensorIndex: Int,
)

data class TfliteSignatureMetadata(
    val key: String,
    val inputs: List<TfliteSignatureTensorMetadata>,
    val outputs: List<TfliteSignatureTensorMetadata>,
)

enum class TfliteTensorElementType {
    FLOAT32,
    INT8,
    UINT8,
    OTHER,
}

data class TfliteModelMetadata(
    val inputs: List<TfliteTensorMetadata>,
    val outputs: List<TfliteTensorMetadata>,
    val signatures: List<TfliteSignatureMetadata>,
) {
    fun resolveInputTensor(): TfliteTensorMetadata {
        return resolveTensor(
            tensors = inputs,
            description = "input tensor",
        ) { tensor ->
            imageTensorSpecOrNull(tensor.shape, expectedChannels = 3) != null
        }
    }

    fun resolveSemanticOutputTensor(
        outputChannels: Int,
    ): TfliteTensorMetadata {
        return resolveTensor(
            tensors = outputs,
            description = "semantic output tensor",
        ) { tensor ->
            imageTensorSpecOrNull(tensor.shape, expectedChannels = outputChannels) != null
        }
    }

    fun outputIndexOf(tensor: TfliteTensorMetadata): Int {
        val index = outputs.indexOf(tensor)
        require(index >= 0) {
            "Output tensor '${tensor.name}' is not part of this model output list."
        }
        return index
    }

    fun resolveSignatureKey(): String {
        return signatures.firstOrNull()?.key
            ?: error("TFLite model does not expose a SignatureDef; unable to use LiteRT named IO.")
    }

    fun resolveSignatureInputName(tensor: TfliteTensorMetadata): String {
        return signatures.firstNotNullOfOrNull { signature ->
            signature.inputs.firstOrNull { it.tensorIndex == tensor.index }?.name
        } ?: error(
            "Unable to resolve signature input name for tensor '${tensor.name}' index=${tensor.index}; " +
                "signatures=$signatures"
        )
    }

    fun resolveSignatureOutputName(tensor: TfliteTensorMetadata): String {
        return signatures.firstNotNullOfOrNull { signature ->
            signature.outputs.firstOrNull { it.tensorIndex == tensor.index }?.name
        } ?: error(
            "Unable to resolve signature output name for tensor '${tensor.name}' index=${tensor.index}; " +
                "signatures=$signatures"
        )
    }

    fun resolveSignatureOutputIndex(tensor: TfliteTensorMetadata): Int {
        signatures.forEach { signature ->
            val outputIndex = signature.outputs.indexOfFirst { it.tensorIndex == tensor.index }
            if (outputIndex >= 0) return outputIndex
        }
        error(
            "Unable to resolve signature output index for tensor '${tensor.name}' index=${tensor.index}; " +
                "signatures=$signatures"
        )
    }

    private fun resolveTensor(
        tensors: List<TfliteTensorMetadata>,
        description: String,
        predicate: (TfliteTensorMetadata) -> Boolean,
    ): TfliteTensorMetadata {
        return tensors.firstOrNull(predicate)
            ?: error("Unable to resolve $description from tensors=${tensors.map { it.name to it.shape }}")
    }
}

object TfliteModelMetadataReader {
    fun read(context: Context, source: ModelSource): TfliteModelMetadata {
        source.openStream(context).use { stream ->
            return read(stream.readBytes())
        }
    }

    fun read(assetManager: AssetManager, assetPath: String): TfliteModelMetadata {
        assetManager.open(assetPath).use { stream ->
            return read(stream.readBytes())
        }
    }

    fun read(bytes: ByteArray): TfliteModelMetadata = read(ByteBuffer.wrap(bytes))

    /**
     * Parses the flatbuffer tables out of [source].
     *
     * Reads only the tables, so a memory-mapped model costs the pages they sit on rather than the
     * whole file. Nothing here touches LiteRT: no compiled model, no accelerator, no inference.
     */
    fun read(source: ByteBuffer): TfliteModelMetadata {
        val buffer = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val model = buffer.u32(0)
        val subgraph = buffer.readTableVector(buffer.field(model, MODEL_SUBGRAPHS_FIELD)).first()
        val tensors = buffer.readTableVector(buffer.field(subgraph, SUBGRAPH_TENSORS_FIELD))
            .mapIndexed { index, tensorOffset -> buffer.readTensor(index, tensorOffset) }
        val inputs = buffer.readIntVector(buffer.field(subgraph, SUBGRAPH_INPUTS_FIELD))
            .map { tensors[it] }
        val outputs = buffer.readIntVector(buffer.field(subgraph, SUBGRAPH_OUTPUTS_FIELD))
            .map { tensors[it] }
        val signatures = buffer.readTableVector(buffer.field(model, MODEL_SIGNATURE_DEFS_FIELD))
            .map { signatureOffset -> buffer.readSignature(signatureOffset) }
        return TfliteModelMetadata(inputs = inputs, outputs = outputs, signatures = signatures)
    }

    private fun ByteBuffer.readTensor(index: Int, offset: Int): TfliteTensorMetadata {
        val typeValue = if (field(offset, TENSOR_TYPE_FIELD) == 0) {
            TENSOR_FLOAT32
        } else {
            get(field(offset, TENSOR_TYPE_FIELD)).toInt()
        }
        return TfliteTensorMetadata(
            index = index,
            name = readString(field(offset, TENSOR_NAME_FIELD)).orEmpty(),
            shape = readIntVector(field(offset, TENSOR_SHAPE_FIELD)),
            elementType = typeValue.toElementType(),
            quantization = readQuantization(field(offset, TENSOR_QUANTIZATION_FIELD)),
        )
    }

    private fun ByteBuffer.readSignature(offset: Int): TfliteSignatureMetadata {
        return TfliteSignatureMetadata(
            key = readString(field(offset, SIGNATURE_KEY_FIELD)).orEmpty(),
            inputs = readTableVector(field(offset, SIGNATURE_INPUTS_FIELD))
                .map { readSignatureTensor(it) },
            outputs = readTableVector(field(offset, SIGNATURE_OUTPUTS_FIELD))
                .map { readSignatureTensor(it) },
        )
    }

    private fun ByteBuffer.readSignatureTensor(offset: Int): TfliteSignatureTensorMetadata {
        return TfliteSignatureTensorMetadata(
            name = readString(field(offset, SIGNATURE_TENSOR_NAME_FIELD)).orEmpty(),
            tensorIndex = readUIntField(field(offset, SIGNATURE_TENSOR_INDEX_FIELD)),
        )
    }

    private fun ByteBuffer.readQuantization(fieldOffset: Int): TensorQuantization? {
        val quantizationOffset = ref(fieldOffset)
        if (quantizationOffset == 0) return null

        val scales = readFloatVector(field(quantizationOffset, QUANTIZATION_SCALE_FIELD))
        if (scales.isEmpty()) return null
        val zeroPoints = readLongVector(field(quantizationOffset, QUANTIZATION_ZERO_POINT_FIELD))
        return TensorQuantization(
            scale = scales.first(),
            zeroPoint = zeroPoints.firstOrNull()?.toInt() ?: 0,
        )
    }

    private fun Int.toElementType(): TfliteTensorElementType {
        return when (this) {
            TENSOR_FLOAT32 -> TfliteTensorElementType.FLOAT32
            TENSOR_UINT8 -> TfliteTensorElementType.UINT8
            TENSOR_INT8 -> TfliteTensorElementType.INT8
            else -> TfliteTensorElementType.OTHER
        }
    }

    private fun ByteBuffer.field(tableOffset: Int, fieldIndex: Int): Int {
        val vtableOffset = tableOffset - getInt(tableOffset)
        val vtableLength = u16(vtableOffset)
        val fieldSlot = VTABLE_HEADER_SIZE + fieldIndex * SHORT_SIZE
        if (fieldSlot + SHORT_SIZE > vtableLength) return 0

        val relativeOffset = u16(vtableOffset + fieldSlot)
        return if (relativeOffset == 0) 0 else tableOffset + relativeOffset
    }

    private fun ByteBuffer.ref(fieldOffset: Int): Int {
        return if (fieldOffset == 0) 0 else fieldOffset + u32(fieldOffset)
    }

    private fun ByteBuffer.readString(fieldOffset: Int): String? {
        val offset = ref(fieldOffset)
        if (offset == 0) return null

        val length = u32(offset)
        val data = ByteArray(length)
        val duplicate = duplicate().order(ByteOrder.LITTLE_ENDIAN)
        duplicate.position(offset + INT_SIZE)
        duplicate.get(data)
        return data.decodeToString()
    }

    private fun ByteBuffer.readIntVector(fieldOffset: Int): List<Int> {
        val offset = ref(fieldOffset)
        if (offset == 0) return emptyList()

        val length = u32(offset)
        return List(length) { index -> getInt(offset + INT_SIZE + index * INT_SIZE) }
    }

    private fun ByteBuffer.readLongVector(fieldOffset: Int): List<Long> {
        val offset = ref(fieldOffset)
        if (offset == 0) return emptyList()

        val length = u32(offset)
        return List(length) { index -> getLong(offset + INT_SIZE + index * LONG_SIZE) }
    }

    private fun ByteBuffer.readFloatVector(fieldOffset: Int): List<Float> {
        val offset = ref(fieldOffset)
        if (offset == 0) return emptyList()

        val length = u32(offset)
        return List(length) { index -> getFloat(offset + INT_SIZE + index * FLOAT_SIZE) }
    }

    private fun ByteBuffer.readUIntField(fieldOffset: Int): Int {
        return if (fieldOffset == 0) 0 else getInt(fieldOffset)
    }

    private fun ByteBuffer.readTableVector(fieldOffset: Int): List<Int> {
        val offset = ref(fieldOffset)
        if (offset == 0) return emptyList()

        val length = u32(offset)
        return List(length) { index ->
            val elementOffset = offset + INT_SIZE + index * INT_SIZE
            elementOffset + u32(elementOffset)
        }
    }

    private fun ByteBuffer.u16(offset: Int): Int {
        return getShort(offset).toInt() and 0xFFFF
    }

    private fun ByteBuffer.u32(offset: Int): Int {
        return getInt(offset)
    }

    private const val SHORT_SIZE = 2
    private const val INT_SIZE = 4
    private const val LONG_SIZE = 8
    private const val FLOAT_SIZE = 4
    private const val VTABLE_HEADER_SIZE = 4

    private const val MODEL_SUBGRAPHS_FIELD = 2
    private const val MODEL_SIGNATURE_DEFS_FIELD = 7
    private const val SUBGRAPH_TENSORS_FIELD = 0
    private const val SUBGRAPH_INPUTS_FIELD = 1
    private const val SUBGRAPH_OUTPUTS_FIELD = 2
    private const val TENSOR_SHAPE_FIELD = 0
    private const val TENSOR_TYPE_FIELD = 1
    private const val TENSOR_NAME_FIELD = 3
    private const val TENSOR_QUANTIZATION_FIELD = 4
    private const val QUANTIZATION_SCALE_FIELD = 2
    private const val QUANTIZATION_ZERO_POINT_FIELD = 3
    private const val SIGNATURE_INPUTS_FIELD = 0
    private const val SIGNATURE_OUTPUTS_FIELD = 1
    private const val SIGNATURE_KEY_FIELD = 2
    private const val SIGNATURE_TENSOR_NAME_FIELD = 0
    private const val SIGNATURE_TENSOR_INDEX_FIELD = 1

    private const val TENSOR_FLOAT32 = 0
    private const val TENSOR_UINT8 = 3
    private const val TENSOR_INT8 = 9
}
