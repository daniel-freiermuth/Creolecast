package com.creolecast.app.airplay2

import java.io.ByteArrayOutputStream

/**
 * Encoder behind [BinaryPlist.encode]: writes a `bplist00` document.
 *
 * Every value becomes its own object, numbered depth-first (a container, then its keys,
 * then its values), with no de-duplication: equal values are simply written twice. Object
 * references and offset-table entries are as wide as the object count and the document
 * size need.
 */
internal object BinaryPlistWriter {

    private const val HEADER = "bplist00"
    private const val TRAILER_UNUSED_BYTES = 6
    private const val TRAILER_FIELD_BYTES = 8

    // Widths of object refs, offset-table entries and integers.
    private const val UINT8_MAX = 0xFFL
    private const val UINT16_MAX = 0xFFFFL
    private const val UINT32_MAX = 0xFFFFFFFFL
    private const val FOUR_BYTES = 4
    private const val EIGHT_BYTES = 8

    // Counts below this fit in the marker's low nibble; 0x0F means "count follows".
    private const val EXTENDED_COUNT = 0x0F

    private const val MARKER_FALSE = 0x08
    private const val MARKER_TRUE = 0x09
    private const val MARKER_INT = 0x10
    private const val MARKER_REAL_64 = 0x23
    private const val MARKER_DATA = 0x40
    private const val MARKER_ASCII_STRING = 0x50
    private const val MARKER_UTF16_STRING = 0x60
    private const val MARKER_ARRAY = 0xA0
    private const val MARKER_DICT = 0xD0

    // log2 of the integer width in bytes, stored in the int marker's low nibble.
    private const val UINT8_EXPONENT = 0
    private const val UINT16_EXPONENT = 1
    private const val UINT32_EXPONENT = 2
    private const val INT64_EXPONENT = 3

    private const val ASCII_LIMIT = 0x80

    fun encode(root: Map<String, Any?>): ByteArray {
        val refSize = widthFor(countObjects(root).toLong())
        val objects = mutableListOf<ByteArray>()
        emit(root, objects, refSize)

        val out = ByteArrayOutputStream()
        out.write(HEADER.toByteArray(Charsets.US_ASCII))
        val offsets = objects.map { bytes -> out.size().toLong().also { out.write(bytes) } }
        val offsetTableStart = out.size().toLong()
        val offsetSize = widthFor(offsetTableStart)
        offsets.forEach { writeBigEndian(it, offsetSize, out) }
        out.write(ByteArray(TRAILER_UNUSED_BYTES))
        out.write(offsetSize)
        out.write(refSize)
        writeBigEndian(objects.size.toLong(), TRAILER_FIELD_BYTES, out)
        writeBigEndian(0L, TRAILER_FIELD_BYTES, out) // root object index
        writeBigEndian(offsetTableStart, TRAILER_FIELD_BYTES, out)
        return out.toByteArray()
    }

    /** Objects [emit] will write for [value]: itself, plus keys and values of containers. */
    private fun countObjects(value: Any?): Int = 1 + when (value) {
        is Map<*, *> -> value.size + value.values.sumOf { countObjects(it) }
        is List<*> -> value.sumOf { countObjects(it) }
        else -> 0
    }

    /** Appends [value] (and its children after it) to [objects]; returns its index. */
    private fun emit(value: Any, objects: MutableList<ByteArray>, refSize: Int): Int {
        val index = objects.size
        objects.add(ByteArray(0)) // reserved: the container is written once its children are
        objects[index] = when (value) {
            is Map<*, *> -> {
                val entries = value.entries.toList()
                // A null key or value is written as the string "null", as it always has been.
                val keyRefs = entries.map { emit(it.key?.toString() ?: "null", objects, refSize) }
                val valueRefs = entries.map { emit(it.value ?: "null", objects, refSize) }
                container(MARKER_DICT, entries.size, keyRefs + valueRefs, refSize)
            }

            is List<*> -> {
                val refs = value.map { emit(it ?: "null", objects, refSize) }
                container(MARKER_ARRAY, refs.size, refs, refSize)
            }

            else -> scalar(value)
        }
        return index
    }

    private fun container(marker: Int, count: Int, refs: List<Int>, refSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeMarker(marker, count, out)
        refs.forEach { writeBigEndian(it.toLong(), refSize, out) }
        return out.toByteArray()
    }

    private fun scalar(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        when (value) {
            is Boolean -> out.write(if (value) MARKER_TRUE else MARKER_FALSE)

            is Float, is Double -> {
                out.write(MARKER_REAL_64)
                writeBigEndian((value as Number).toDouble().toRawBits(), EIGHT_BYTES, out)
            }

            is Number -> writeInteger(value.toLong(), out)

            is ByteArray -> {
                writeMarker(MARKER_DATA, value.size, out)
                out.write(value)
            }

            else -> writeString(value.toString(), out)
        }
        return out.toByteArray()
    }

    /** ASCII strings as such; anything else as UTF-16, its count in code units. */
    private fun writeString(value: String, out: ByteArrayOutputStream) {
        if (value.all { it.code < ASCII_LIMIT }) {
            writeMarker(MARKER_ASCII_STRING, value.length, out)
            out.write(value.toByteArray(Charsets.US_ASCII))
        } else {
            writeMarker(MARKER_UTF16_STRING, value.length, out)
            out.write(value.toByteArray(Charsets.UTF_16BE))
        }
    }

    /** Non-negative values in the narrowest width; negative ones as 8-byte two's complement. */
    private fun writeInteger(value: Long, out: ByteArrayOutputStream) {
        val exponent = when (value) {
            in 0..UINT8_MAX -> UINT8_EXPONENT
            in 0..UINT16_MAX -> UINT16_EXPONENT
            in 0..UINT32_MAX -> UINT32_EXPONENT
            else -> INT64_EXPONENT
        }
        out.write(MARKER_INT or exponent)
        writeBigEndian(value, 1 shl exponent, out)
    }

    /** Object marker with its element/byte count, extended when it does not fit the nibble. */
    private fun writeMarker(marker: Int, count: Int, out: ByteArrayOutputStream) {
        if (count < EXTENDED_COUNT) {
            out.write(marker or count)
        } else {
            out.write(marker or EXTENDED_COUNT)
            writeInteger(count.toLong(), out)
        }
    }

    /** Bytes needed for unsigned [max]: 1, 2, 4 or 8. */
    private fun widthFor(max: Long): Int = when {
        max <= UINT8_MAX -> 1
        max <= UINT16_MAX -> 2
        max <= UINT32_MAX -> FOUR_BYTES
        else -> EIGHT_BYTES
    }

    /** Low [size] bytes of [value], most significant first. */
    private fun writeBigEndian(value: Long, size: Int, out: ByteArrayOutputStream) {
        for (i in size - 1 downTo 0) {
            out.write((value shr (i * 8)).toInt())
        }
    }
}
