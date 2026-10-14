package com.sailens.guidance.trace.capture

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * `classmaps.bin`: the semantic class maps of a model-regression record, appended one entry per
 * semantic run. Thirty a second would be 100k files an hour, so they share one file; each frame's
 * [RegressionFrameRecord] points at its entry with a [ClassMapRef].
 *
 * An entry, big-endian:
 *
 * ```
 * u32  MAGIC ("SCM1")
 * i64  frame sequence number
 * i32  width
 * i32  height
 * u32  CRC32 of the width*height class ids, one unsigned byte each
 * i32  n = length of the compressed bytes
 * n    the class ids, zlib-compressed (deflate level 1)
 * ```
 *
 * A torn last entry (an interrupted capture) is never referenced: its frame record is written after
 * the entry is complete.
 */
object ClassMapStore {
    const val ENCODING: String = "zlib-u8"
    const val MAGIC: Int = 0x53434D31
    private const val HEADER_BYTES = 4 + 8 + 4 + 4 + 4 + 4

    /** One encoded entry, ready to append. */
    class Entry internal constructor(
        val seq: Long,
        val width: Int,
        val height: Int,
        val crc32: Long,
        val bytes: ByteArray,
    ) {
        fun ref(offset: Long): ClassMapRef = ClassMapRef(offset, bytes.size, width, height, crc32)
    }

    /** Encodes one class map with a throwaway [Encoder]; for tests and tools. */
    fun encode(seq: Long, width: Int, height: Int, classIds: IntArray): Entry =
        Encoder().use { it.encode(seq, width, height, classIds) }

    /**
     * Encodes class maps one after another, reusing its deflater and buffers: the recorder encodes
     * one per semantic run, about thirty a second. Not thread-safe; [close] releases the deflater's
     * native memory.
     */
    class Encoder : AutoCloseable {
        private val deflater = Deflater(Deflater.BEST_SPEED)
        private var raw = ByteArray(0)
        private val chunk = ByteArray(16 * 1024)
        private val compressed = ByteArrayOutputStream()

        /**
         * Class ids must fit an unsigned byte (a semantic model with 256 or more classes needs
         * another encoding, so this refuses rather than truncate).
         */
        fun encode(seq: Long, width: Int, height: Int, classIds: IntArray): Entry {
            val pixels = width * height
            require(width > 0 && height > 0 && classIds.size >= pixels) {
                "class map ${width}x$height needs $pixels ids, got ${classIds.size}"
            }
            if (raw.size < pixels) raw = ByteArray(pixels)
            for (i in 0 until pixels) {
                val id = classIds[i]
                require(id in 0..255) { "class id $id at $i does not fit $ENCODING" }
                raw[i] = id.toByte()
            }
            val crc = CRC32().apply { update(raw, 0, pixels) }.value
            deflater.reset()
            deflater.setInput(raw, 0, pixels)
            deflater.finish()
            compressed.reset()
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                compressed.write(chunk, 0, n)
            }
            val entry = ByteArrayOutputStream(HEADER_BYTES + compressed.size())
            DataOutputStream(entry).use { data ->
                data.writeInt(MAGIC)
                data.writeLong(seq)
                data.writeInt(width)
                data.writeInt(height)
                data.writeInt(crc.toInt())
                data.writeInt(compressed.size())
                compressed.writeTo(data)
            }
            return Entry(seq, width, height, crc, entry.toByteArray())
        }

        override fun close() = deflater.end()
    }

    /**
     * Reads the class map [ref] points at in [file], checking that the entry is the one the record
     * expects -- magic, size and CRC.
     *
     * @param seq the frame the entry must belong to.
     * @throws IOException when the entry is missing, torn or does not match.
     */
    fun read(file: File, ref: ClassMapRef, seq: Long): IntArray {
        val entry = ByteArray(ref.length)
        RandomAccessFile(file, "r").use { raf ->
            if (ref.offset < 0 || ref.offset + ref.length > raf.length()) {
                throw IOException("class map entry for frame $seq is outside ${file.name}")
            }
            raf.seek(ref.offset)
            raf.readFully(entry)
        }
        val data = DataInputStream(entry.inputStream())
        if (data.readInt() != MAGIC) throw IOException("frame $seq: no class map entry at offset ${ref.offset}")
        val entrySeq = data.readLong()
        val width = data.readInt()
        val height = data.readInt()
        val crc = data.readInt().toLong() and 0xFFFF_FFFFL
        val n = data.readInt()
        if (entrySeq != seq || width != ref.width || height != ref.height || crc != ref.crc32) {
            throw IOException("frame $seq: class map entry is for frame $entrySeq ${width}x$height, not the one recorded")
        }
        if (n != ref.length - HEADER_BYTES) throw IOException("frame $seq: class map entry length mismatch")

        val raw = ByteArray(width * height)
        val inflater = Inflater()
        try {
            inflater.setInput(entry, HEADER_BYTES, n)
            var done = 0
            while (done < raw.size) {
                val got = inflater.inflate(raw, done, raw.size - done)
                if (got == 0) break
                done += got
            }
            if (done != raw.size || !inflater.finished()) throw IOException("frame $seq: class map does not inflate to ${width}x$height")
        } catch (e: java.util.zip.DataFormatException) {
            throw IOException("frame $seq: corrupt class map", e)
        } finally {
            inflater.end()
        }
        if ((CRC32().apply { update(raw) }.value) != ref.crc32) throw IOException("frame $seq: class map CRC mismatch")
        return IntArray(raw.size) { raw[it].toInt() and 0xFF }
    }
}
