package com.creolecast.app.airplay2

import java.io.InputStream

/** An RTSP/HTTP reply on the control channel; code 0 when nothing could be read. */
internal class RtspResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray?) {
    companion object {
        val EMPTY = RtspResponse(0, emptyMap(), null)
    }
}

/** Start line and header lines of a message; [complete] is false if the stream ended first. */
internal class RtspHead(val lines: List<String>, val complete: Boolean)

/** Parsed header lines plus the (last) Content-Length among them, 0 if none. */
internal class RtspHeaders(val values: Map<String, String>, val contentLength: Int)

/** Reading RTSP-framed messages, shared by the control channel and the event channel. */
internal object RtspWire {

    /** Reads up to and including the blank line that ends the head. */
    fun readHead(input: InputStream): RtspHead {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return RtspHead(lines, complete = false)
            line.append(b.toChar())
            if (b == '\n'.code) {
                val text = line.toString().trimEnd('\r', '\n')
                line.clear()
                if (text.isEmpty()) return RtspHead(lines, complete = true)
                lines.add(text)
            }
        }
    }

    /** Header lines after the start line, in order; a later duplicate key wins. */
    fun parseHeaders(head: List<String>): RtspHeaders {
        val headers = linkedMapOf<String, String>()
        var contentLength = 0
        for (i in 1 until head.size) {
            val parts = head[i].split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                headers[key] = value
                if (key.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }
        return RtspHeaders(headers, contentLength)
    }

    /** Fills [buf] from [input]; returns how many bytes arrived before the stream ended. */
    fun readFully(input: InputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }
}
