package com.creolecast.app.airplay2

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encoder behind [BinaryPlist.encode]: writes a `bplist00` document. */
internal object BinaryPlistWriter {

    private const val HEADER = "bplist00"
    private const val TRAILER_SIZE = 32
    private const val TRAILER_UNUSED_BYTES = 6

    // Offset-table entries and object refs widen once values no longer fit.
    private const val ONE_BYTE_LIMIT = 0x100
    private const val TWO_BYTE_LIMIT = 0x10000
    private const val FOUR_BYTES = 4

    // Counts below this fit in the marker's low nibble; 0x0F means "count follows".
    private const val INLINE_COUNT_LIMIT = 15
    private const val EXTENDED_COUNT = 0x0F

    private const val MARKER_FALSE = 0x08
    private const val MARKER_TRUE = 0x09
    private const val MARKER_INT = 0x10
    private const val MARKER_DATA = 0x40
    private const val MARKER_ASCII_STRING = 0x50
    private const val MARKER_UTF16_STRING = 0x60
    private const val MARKER_ARRAY = 0xA0
    private const val MARKER_DICT = 0xD0

    private const val UINT8_MAX = 0xFFL
    private const val UINT16_MAX = 0xFFFFL
    private const val UINT32_MAX = 0xFFFFFFFFL

    // Strings with any char at or above this need the UTF-16BE string type.
    private const val ASCII_LIMIT = 0x80

    // log2 of the integer width in bytes, stored in the int marker's low nibble.
    private const val UINT8_EXPONENT = 0
    private const val UINT16_EXPONENT = 1
    private const val UINT32_EXPONENT = 2
    private const val UINT64_EXPONENT = 3

    private sealed interface PlistNode {
        data class PDict(val entries: List<Pair<String, PlistNode>>) : PlistNode
        data class PArray(val items: List<PlistNode>) : PlistNode
        data class PString(val value: String) : PlistNode
        data class PUint(val value: Long) : PlistNode
        data class PBool(val value: Boolean) : PlistNode
        data class PData(val value: ByteArray) : PlistNode
    }

    fun encode(root: Map<String, Any?>): ByteArray {
        val objects = mutableListOf<PlistNode>()
        collectObjects(toNode(root), objects, mutableMapOf())
        val objectsOut = ByteArrayOutputStream()
        val offsets = objects.map { obj ->
            val offset = HEADER.length + objectsOut.size() // absolute: after the header
            writeObject(obj, objects, objectsOut)
            offset
        }
        val offsetTableStart = HEADER.length + objectsOut.size() // absolute position in output
        val maxOffset = offsetTableStart - 1
        val offsetSize = when {
            maxOffset < ONE_BYTE_LIMIT -> 1
            maxOffset < TWO_BYTE_LIMIT -> 2
            else -> FOUR_BYTES
        }
        offsets.forEach { writeBigEndian(it.toLong(), offsetSize, objectsOut) }
        val trailer = ByteBuffer.allocate(TRAILER_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
            put(ByteArray(TRAILER_UNUSED_BYTES))
            put(offsetSize.toByte()) // 1 byte: offset table entry size
            put(objectRefSize(objects.size).toByte()) // 1 byte: object reference size
            putLong(objects.size.toLong()) // 8 bytes: number of objects
            putLong(0L) // 8 bytes: root object index
            putLong(offsetTableStart.toLong()) // 8 bytes: offset table offset
        }.array()
        return ByteArrayOutputStream().apply {
            write(HEADER.toByteArray())
            write(objectsOut.toByteArray())
            write(trailer)
        }.toByteArray()
    }

    private fun collectObjects(
        node: PlistNode,
        list: MutableList<PlistNode>,
        seen: MutableMap<Int, Int>
    ) {
        val hc = node.hashCode()
        if (hc in seen) return
        seen[hc] = list.size
        list.add(node)
        when (node) {
            is PlistNode.PDict -> node.entries.forEach { (k, v) ->
                collectObjects(PlistNode.PString(k), list, seen)
                collectObjects(v, list, seen)
            }

            is PlistNode.PArray -> node.items.forEach { collectObjects(it, list, seen) }

            is PlistNode.PString, is PlistNode.PUint, is PlistNode.PBool, is PlistNode.PData -> Unit
        }
    }

    // A null key or value is written as the string "null", as it always has been.
    private fun toNode(value: Any?): PlistNode = when (value) {
        is Map<*, *> -> PlistNode.PDict(
            value.entries.map { (k, v) -> (k?.toString() ?: "null") to toNode(v) }
        )

        is List<*> -> PlistNode.PArray(value.map { toNode(it) })

        is String -> PlistNode.PString(value)

        is Number -> PlistNode.PUint(value.toLong())

        is Boolean -> PlistNode.PBool(value)

        is ByteArray -> PlistNode.PData(value)

        null -> PlistNode.PString("null")

        else -> PlistNode.PString(value.toString())
    }

    private fun findIndex(node: PlistNode, objects: List<PlistNode>): Int {
        val hc = node.hashCode()
        for (i in objects.indices) {
            if (objects[i].hashCode() == hc && objects[i] == node) return i
        }
        return 0
    }

    private fun writeObject(obj: PlistNode, objects: List<PlistNode>, out: ByteArrayOutputStream) {
        when (obj) {
            is PlistNode.PDict -> {
                val keys = obj.entries.map { findIndex(PlistNode.PString(it.first), objects) }
                val values = obj.entries.map { findIndex(it.second, objects) }
                writeMarker(MARKER_DICT, obj.entries.size, out)
                keys.forEach { writeIntRef(it, objects.size, out) }
                values.forEach { writeIntRef(it, objects.size, out) }
            }

            is PlistNode.PArray -> {
                writeMarker(MARKER_ARRAY, obj.items.size, out)
                obj.items.forEach { writeIntRef(findIndex(it, objects), objects.size, out) }
            }

            is PlistNode.PString -> {
                // ASCII strings use the 0x5 marker; anything else must be 0x6
                // UTF-16BE, whose count is in 16-bit code units (String.length).
                val ascii = obj.value.all { it.code < ASCII_LIMIT }
                val marker = if (ascii) MARKER_ASCII_STRING else MARKER_UTF16_STRING
                writeMarker(marker, obj.value.length, out)
                val charset = if (ascii) Charsets.US_ASCII else Charsets.UTF_16BE
                out.write(obj.value.toByteArray(charset))
            }

            is PlistNode.PUint -> writeUint(obj.value, out)

            is PlistNode.PBool -> out.write(if (obj.value) MARKER_TRUE else MARKER_FALSE)

            is PlistNode.PData -> {
                writeMarker(MARKER_DATA, obj.value.size, out)
                out.write(obj.value)
            }
        }
    }

    private fun writeUint(value: Long, out: ByteArrayOutputStream) {
        val exponent = when (value) {
            in 0..UINT8_MAX -> UINT8_EXPONENT
            in 0..UINT16_MAX -> UINT16_EXPONENT
            in 0..UINT32_MAX -> UINT32_EXPONENT
            else -> UINT64_EXPONENT
        }
        out.write(MARKER_INT or exponent)
        writeBigEndian(value, 1 shl exponent, out)
    }

    /** Object marker with its element/byte count, extended when it does not fit the nibble. */
    private fun writeMarker(marker: Int, count: Int, out: ByteArrayOutputStream) {
        if (count < INLINE_COUNT_LIMIT) {
            out.write(marker or count)
        } else {
            out.write(marker or EXTENDED_COUNT)
            writeInt(count, out)
        }
    }

    private fun writeInt(value: Int, out: ByteArrayOutputStream) {
        if (value < EXTENDED_COUNT) {
            out.write(value)
        } else if (value < ONE_BYTE_LIMIT) {
            out.write(MARKER_INT or UINT8_EXPONENT)
            out.write(value)
        } else {
            out.write(MARKER_INT or UINT16_EXPONENT)
            writeBigEndian(value.toLong(), 2, out)
        }
    }

    private fun objectRefSize(numObjects: Int): Int = if (numObjects <= ONE_BYTE_LIMIT) 1 else 2

    private fun writeIntRef(index: Int, numObjects: Int, out: ByteArrayOutputStream) {
        if (objectRefSize(numObjects) == 1) {
            out.write(index)
        } else {
            writeBigEndian(index.toLong(), 2, out)
        }
    }

    /** Low [size] bytes of [value], most significant first. */
    private fun writeBigEndian(value: Long, size: Int, out: ByteArrayOutputStream) {
        for (i in size - 1 downTo 0) {
            out.write((value shr (i * 8)).toInt())
        }
    }
}
