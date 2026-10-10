package com.creolecast.app.cast

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One RTSP request as the RAOP sender writes it, addressed to rtsp://[host]:[port]/AriaCast. */
internal data class RtspRequest(
    val method: String,
    val host: String,
    val port: Int,
    val cseq: Int,
    val headers: Map<String, String>,
    val body: String? = null
)

/** Plain-text RTSP framing for the RAOP (AirPlay 1) control connection. */
internal object RtspWire {
    fun send(output: OutputStream, request: RtspRequest) {
        val text = StringBuilder()
        text.append("${request.method} rtsp://${request.host}:${request.port}/AriaCast")
        text.append(" RTSP/1.0\r\n")
        text.append("CSeq: ${request.cseq}\r\n")
        request.headers.forEach { (k, v) -> text.append("$k: $v\r\n") }
        text.append("\r\n")
        if (request.body != null) text.append(request.body)
        output.write(text.toString().toByteArray())
        output.flush()
    }

    /** Reads the status line and headers of one response; read errors end it early. */
    fun readResponse(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        try {
            var line = readLine(input)
            while (!line.isNullOrEmpty()) {
                lines.add(line)
                line = readLine(input)
            }
        } catch (e: IOException) {
            Log.e(LOG_TAG, "Error reading RTSP response: ${e.reason}")
        }
        val status = lines.firstOrNull()
        if (status != null && !status.contains("200 OK") && !status.contains("100 Continue")) {
            Log.w(LOG_TAG, "RTSP Non-OK Response: $status")
        }
        return lines
    }

    /** One CRLF- or LF-terminated line without its terminator; null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        var b = input.read()
        while (b != -1 && b.toChar() != '\n') {
            if (b.toChar() != '\r') line.append(b.toChar())
            b = input.read()
        }
        return if (b == -1 && line.isEmpty()) null else line.toString()
    }
}
