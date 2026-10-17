package com.creolecast.app.googlecast

import java.io.ByteArrayOutputStream
import java.io.IOException

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH_DELIMITED = 2
private const val WIRE_FIXED32 = 5
private const val FIXED64_BYTES = 8
private const val FIXED32_BYTES = 4

private const val FIELD_PROTOCOL_VERSION = 1
private const val FIELD_SOURCE_ID = 2
private const val FIELD_DESTINATION_ID = 3
private const val FIELD_NAMESPACE = 4
private const val FIELD_PAYLOAD_TYPE = 5
private const val FIELD_PAYLOAD_UTF8 = 6

private const val PAYLOAD_TYPE_STRING = 0L
private const val VARINT_PAYLOAD_MASK = 0x7F
private const val VARINT_CONTINUATION = 0x80
private const val VARINT_MAX_SHIFT = 63
private const val VARINT_BITS_PER_BYTE = 7
private const val TAG_WIRE_TYPE_BITS = 3
private const val TAG_WIRE_TYPE_MASK = 7L

/**
 * The CastV2 wire message (cast_channel.proto `CastMessage`), hand-encoded so the app
 * doesn't need a protobuf runtime:
 *
 *   1 protocol_version (enum, CASTV2_1_0 = 0)   2 source_id (string)
 *   3 destination_id (string)                   4 namespace (string)
 *   5 payload_type (enum, STRING = 0, BINARY = 1)
 *   6 payload_utf8 (string)                     7 payload_binary (bytes)
 *
 * On the socket each message is preceded by its length as a 32-bit big-endian int. This
 * sender only produces string payloads; binary ones (device authentication) are decoded
 * as having no [payloadUtf8] and ignored.
 */
internal class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String?
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeVarintField(FIELD_PROTOCOL_VERSION, 0)
        out.writeBytesField(FIELD_SOURCE_ID, sourceId.toByteArray(Charsets.UTF_8))
        out.writeBytesField(FIELD_DESTINATION_ID, destinationId.toByteArray(Charsets.UTF_8))
        out.writeBytesField(FIELD_NAMESPACE, namespace.toByteArray(Charsets.UTF_8))
        out.writeVarintField(FIELD_PAYLOAD_TYPE, PAYLOAD_TYPE_STRING)
        out.writeBytesField(FIELD_PAYLOAD_UTF8, payloadUtf8.orEmpty().toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    companion object {
        /** Receivers' messages stay far below this; anything larger is a broken frame. */
        const val MAX_MESSAGE_SIZE = 64 * 1024

        /** Parses one message (without its length prefix); throws [IOException] if malformed. */
        fun decode(data: ByteArray): CastMessage {
            val reader = ProtoReader(data)
            var source = ""
            var destination = ""
            var namespace = ""
            var utf8: String? = null
            while (reader.hasMore()) {
                val tag = reader.varint()
                val field = (tag ushr TAG_WIRE_TYPE_BITS).toInt()
                when ((tag and TAG_WIRE_TYPE_MASK).toInt()) {
                    WIRE_VARINT -> reader.varint()

                    WIRE_FIXED64 -> reader.skip(FIXED64_BYTES)

                    WIRE_FIXED32 -> reader.skip(FIXED32_BYTES)

                    WIRE_LENGTH_DELIMITED -> {
                        val bytes = reader.lengthDelimited()
                        when (field) {
                            FIELD_SOURCE_ID -> source = String(bytes, Charsets.UTF_8)
                            FIELD_DESTINATION_ID -> destination = String(bytes, Charsets.UTF_8)
                            FIELD_NAMESPACE -> namespace = String(bytes, Charsets.UTF_8)
                            FIELD_PAYLOAD_UTF8 -> utf8 = String(bytes, Charsets.UTF_8)
                        }
                    }

                    else -> throw IOException("unsupported protobuf wire type in field $field")
                }
            }
            return CastMessage(source, destination, namespace, utf8)
        }

        private fun ByteArrayOutputStream.writeVarint(value: Long) {
            var v = value
            while (v and VARINT_PAYLOAD_MASK.toLong().inv() != 0L) {
                write(
                    ((v and VARINT_PAYLOAD_MASK.toLong()) or VARINT_CONTINUATION.toLong()).toInt()
                )
                v = v ushr VARINT_BITS_PER_BYTE
            }
            write(v.toInt())
        }

        private fun ByteArrayOutputStream.writeVarintField(field: Int, value: Long) {
            writeVarint((field shl TAG_WIRE_TYPE_BITS).toLong())
            writeVarint(value)
        }

        private fun ByteArrayOutputStream.writeBytesField(field: Int, value: ByteArray) {
            writeVarint(((field shl TAG_WIRE_TYPE_BITS) or WIRE_LENGTH_DELIMITED).toLong())
            writeVarint(value.size.toLong())
            write(value)
        }
    }
}

/** Sequential reader over one protobuf message. */
private class ProtoReader(private val data: ByteArray) {
    private var pos = 0

    fun hasMore() = pos < data.size

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= data.size) throw IOException("truncated varint")
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and VARINT_PAYLOAD_MASK).toLong() shl shift)
            if (b and VARINT_CONTINUATION == 0) return result
            shift += VARINT_BITS_PER_BYTE
            if (shift > VARINT_MAX_SHIFT) throw IOException("varint too long")
        }
    }

    fun skip(count: Int) {
        if (pos + count > data.size) throw IOException("truncated field")
        pos += count
    }

    fun lengthDelimited(): ByteArray {
        val len = varint()
        if (len < 0 || pos + len > data.size) throw IOException("bad field length $len")
        return data.copyOfRange(pos, pos + len.toInt()).also { pos += len.toInt() }
    }
}
