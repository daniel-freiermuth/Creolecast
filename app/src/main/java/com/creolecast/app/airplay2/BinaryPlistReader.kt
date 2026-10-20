package com.creolecast.app.airplay2

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoder behind [BinaryPlist.decode]. Parses the `bplist00` trailer and
 * offset table up front. Every read is bounds-checked, so malformed or
 * truncated input fails with [IllegalArgumentException] only.
 */
internal class BinaryPlistReader(private val data: ByteArray) {

    private companion object {
        const val HEADER = "bplist00"
        const val TRAILER_SIZE = 32
        const val TRAILER_UNUSED_BYTES = 6
        const val MIN_SIZE = 40 // 8-byte header + 32-byte trailer

        // Marker byte: object type in the high nibble, size/count in the low one.
        const val TYPE_SHIFT = 4
        const val LOW_NIBBLE = 0x0F
        const val EXTENDED_COUNT = 0x0F

        const val TYPE_SIMPLE = 0x00
        const val TYPE_INT = 0x01
        const val TYPE_REAL = 0x02
        const val TYPE_DATE = 0x03
        const val TYPE_DATA = 0x04
        const val TYPE_ASCII_STRING = 0x05
        const val TYPE_UTF16_STRING = 0x06
        const val TYPE_ARRAY = 0x08
        const val TYPE_SET = 0x0A
        const val TYPE_DICT = 0x0D

        const val MARKER_FALSE = 0x08
        const val MARKER_TRUE = 0x09

        const val FLOAT_SIZE = 4
        const val DOUBLE_SIZE = 8

        // AirPlay plists nest a few levels; anything deeper is a reference cycle.
        const val MAX_NESTING_DEPTH = 128
    }

    private val objectRefSize: Int
    private val rootIndex: Int
    private val objectOffsets: IntArray
    private var depth = 0

    // Each object is decoded once and shared by every ref to it. Decoding per
    // ref lets a few KB of collections that reference the next level many
    // times expand exponentially and exhaust memory.
    private val decoded = HashMap<Int, Any?>()

    init {
        require(data.size >= MIN_SIZE) { "too short" }
        val magic = data.copyOf(HEADER.length).toString(Charsets.UTF_8)
        require(magic == HEADER) { "not a binary plist" }

        val trailer = data.copyOfRange(data.size - TRAILER_SIZE, data.size)
        val buf = ByteBuffer.wrap(trailer).order(ByteOrder.BIG_ENDIAN)
        buf.position(TRAILER_UNUSED_BYTES) // skip the unused trailer bytes
        val offsetIntSize = buf.get().toInt() and BYTE_MASK
        objectRefSize = buf.get().toInt() and BYTE_MASK
        val objectCount = buf.getLong().toInt()
        rootIndex = buf.getLong().toInt()
        val offsetTableOffset = buf.getLong().toInt()
        // Object refs are at least one byte, and every object takes at least one
        // byte, so a larger count is corrupt (and would only exhaust memory).
        require(objectRefSize > 0 && objectCount in 0..data.size) {
            "invalid binary plist trailer"
        }
        data.requireRange(offsetTableOffset, objectCount.toLong() * offsetIntSize)

        objectOffsets = IntArray(objectCount) { i ->
            data.readSizedInt(offsetTableOffset + i * offsetIntSize, offsetIntSize)
        }
    }

    /** The root object if it is a dictionary, else an empty map. */
    fun readRoot(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (readObject(rootIndex) as? Map<String, Any?>).orEmpty()
    }

    private fun readObject(objIndex: Int): Any? {
        if (decoded.containsKey(objIndex)) return decoded[objIndex]
        require(depth < MAX_NESTING_DEPTH) { "binary plist nested too deeply" }
        depth++
        try {
            // Stored only once complete, so a reference cycle still recurses
            // into the depth limit above.
            return readObjectAt(objIndex).also { decoded[objIndex] = it }
        } finally {
            depth--
        }
    }

    private fun readObjectAt(objIndex: Int): Any? {
        require(objIndex in objectOffsets.indices) { "invalid object reference $objIndex" }
        val off = objectOffsets[objIndex]
        data.requireRange(off, 1)
        val marker = data[off].toInt() and BYTE_MASK
        val objSize = marker and LOW_NIBBLE
        return when (marker shr TYPE_SHIFT) {
            TYPE_SIMPLE -> simpleValue(marker)

            TYPE_INT -> readInteger(off, objSize)

            TYPE_REAL, TYPE_DATE -> readReal(off, objSize)

            TYPE_DATA -> {
                val (cnt, pos) = readCount(off + 1, objSize)
                data.requireRange(pos, cnt.toLong())
                data.copyOfRange(pos, pos + cnt)
            }

            TYPE_ASCII_STRING -> {
                val (cnt, pos) = readCount(off + 1, objSize)
                data.requireRange(pos, cnt.toLong())
                String(data, pos, cnt, Charsets.US_ASCII)
            }

            TYPE_UTF16_STRING -> {
                val (cnt, pos) = readCount(off + 1, objSize)
                data.requireRange(pos, cnt * 2L)
                String(data, pos, cnt * 2, Charsets.UTF_16BE)
            }

            TYPE_ARRAY, TYPE_SET -> readArray(off, objSize)

            TYPE_DICT -> readDict(off, objSize)

            else -> null
        }
    }

    private fun simpleValue(marker: Int): Boolean? = when (marker) {
        MARKER_FALSE -> false
        MARKER_TRUE -> true
        else -> null // 0x00 null, 0x0F fill and other simple markers
    }

    // 1-, 2- and 4-byte integers are unsigned; only 8-byte ones are signed.
    private fun readInteger(off: Int, objSize: Int): Long = data.readSizedLong(
        off + 1,
        1 shl objSize
    )

    private fun readReal(off: Int, objSize: Int): Double = when (val bytes = 1 shl objSize) {
        FLOAT_SIZE -> Float.fromBits(data.readSizedInt(off + 1, FLOAT_SIZE)).toDouble()
        DOUBLE_SIZE -> Double.fromBits(data.readSizedLong(off + 1, DOUBLE_SIZE))
        else -> throw IllegalArgumentException("unsupported real size $bytes")
    }

    private fun readArray(off: Int, objSize: Int): List<Any?> {
        val (cnt, pos) = readCount(off + 1, objSize)
        // A negative count reads as empty, as it always has.
        data.requireRange(pos, cnt.coerceAtLeast(0).toLong() * objectRefSize)
        return (0 until cnt).map {
            readObject(data.readSizedInt(pos + it * objectRefSize, objectRefSize))
        }
    }

    private fun readDict(off: Int, objSize: Int): Map<String, Any?> {
        val (cnt, pos) = readCount(off + 1, objSize)
        // Key refs then value refs; a negative count reads as empty, as it always has.
        data.requireRange(pos, 2L * cnt.coerceAtLeast(0) * objectRefSize)
        val keys = (0 until cnt).map {
            val key = readObject(data.readSizedInt(pos + it * objectRefSize, objectRefSize))
            key?.toString().orEmpty()
        }
        val vStart = pos + cnt * objectRefSize
        val values = (0 until cnt).map {
            readObject(data.readSizedInt(vStart + it * objectRefSize, objectRefSize))
        }
        val dict = linkedMapOf<String, Any?>()
        for (j in 0 until cnt) dict[keys[j]] = values[j]
        return dict
    }

    /** Returns (count, position of the first payload byte), following an extended count. */
    private fun readCount(dataPos: Int, count: Int): Pair<Int, Int> {
        if (count != EXTENDED_COUNT) return count to dataPos
        val marker = data.readSizedInt(dataPos, 1)
        val valBytes = 1 shl (marker and LOW_NIBBLE)
        val resolved = data.readSizedInt(dataPos + 1, valBytes)
        return resolved to (dataPos + 1 + valBytes)
    }
}

private const val BYTE_MASK = 0xFF

/**
 * Fails with [IllegalArgumentException] unless [length] bytes at [offset] lie inside
 * the array. Zero bytes touch nothing, so any offset is fine for them.
 */
private fun ByteArray.requireRange(offset: Int, length: Long) {
    val inBounds = offset >= 0 && offset + length <= size
    require(length == 0L || (length > 0 && inBounds)) {
        "binary plist truncated: $length bytes at $offset, size $size"
    }
}

private fun ByteArray.readSizedInt(offset: Int, bytes: Int): Int {
    requireRange(offset, bytes.toLong())
    var v = 0
    for (i in 0 until bytes) {
        v = (v shl 8) or (this[offset + i].toInt() and BYTE_MASK)
    }
    return v
}

private fun ByteArray.readSizedLong(offset: Int, bytes: Int): Long {
    requireRange(offset, bytes.toLong())
    var v = 0L
    for (i in 0 until bytes) {
        v = (v shl 8) or (this[offset + i].toLong() and BYTE_MASK.toLong())
    }
    return v
}
