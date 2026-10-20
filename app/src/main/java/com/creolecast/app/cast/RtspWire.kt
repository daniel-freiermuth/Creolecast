package com.creolecast.app.cast

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

/** Upper bounds for one reply, against a receiver that never stops sending. */
private const val MAX_HEADER_LINES = 64
private const val MAX_BODY_BYTES = 1 shl 20

/** One RTSP request as the RAOP sender writes it, addressed to [uri]. */
internal class RtspRequest(
    val method: String,
    val host: String,
    val port: Int,
    val cseq: Int,
    val headers: Map<String, String>,
    val body: ByteArray? = null
) {
    val uri: String get() = rtspUri(host, port)
}

/** The request URI of every RAOP request to [host]:[port]. */
internal fun rtspUri(host: String, port: Int) = "rtsp://$host:$port/AriaCast"

/** A receiver's reply: status code (0 if unreadable), headers by lower-case name, body. */
internal class RtspReply(val code: Int, val headers: Map<String, String>, val body: ByteArray) {
    val isSuccess: Boolean get() = code in SUCCESS

    operator fun get(header: String): String? = headers[header.lowercase()]

    private companion object {
        val SUCCESS = 200..299
    }
}

/** Plain-text RTSP framing for the RAOP (AirPlay 1) control connection. */
internal object RtspWire {
    fun send(output: OutputStream, request: RtspRequest) {
        val text = StringBuilder()
        text.append("${request.method} ${request.uri} RTSP/1.0\r\n")
        text.append("CSeq: ${request.cseq}\r\n")
        request.headers.forEach { (k, v) -> text.append("$k: $v\r\n") }
        text.append("\r\n")
        output.write(text.toString().toByteArray() + (request.body ?: ByteArray(0)))
        output.flush()
    }

    /** Reads one reply, its body included; throws [IOException] if the connection ends first. */
    fun readReply(input: InputStream): RtspReply {
        var statusLine = requireLine(input)
        // Tolerate a stray blank line between messages.
        while (statusLine.isEmpty()) statusLine = requireLine(input)
        val headers = mutableMapOf<String, String>()
        var line = readLine(input)
        while (!line.isNullOrEmpty()) {
            if (headers.size >= MAX_HEADER_LINES) throw IOException("RTSP reply header too long")
            val colon = line.indexOf(':')
            if (colon >
                0
            ) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
            line = readLine(input)
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY_BYTES) throw IOException("bad Content-Length $length")
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        return RtspReply(code, headers, readBody(input, length))
    }

    private fun requireLine(input: InputStream): String =
        readLine(input) ?: throw IOException("connection closed by the receiver")

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("connection closed inside a reply body")
            read += n
        }
        return body
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

/**
 * RFC 2617 Digest authentication (no qop) as password-protected RAOP receivers ask for it:
 * user `iTunes`, the receiver's password, and the realm/nonce of its 401 challenge.
 */
internal class DigestAuth(private val password: String) {
    private var realm: String? = null
    private var nonce: String? = null

    /** Takes a `WWW-Authenticate` challenge; false if it isn't a usable Digest one. */
    fun challenge(header: String?): Boolean {
        if (header == null || !header.trim().startsWith("Digest", ignoreCase = true)) return false
        realm = param(header, "realm")
        nonce = param(header, "nonce")
        return realm != null && nonce != null
    }

    /** The `Authorization` header for [method] on [uri], once challenged. */
    fun authorization(method: String, uri: String): String? {
        val r = realm
        val n = nonce
        if (r == null || n == null) return null
        val ha1 = md5Hex("$USER:$r:$password")
        val ha2 = md5Hex("$method:$uri")
        val response = md5Hex("$ha1:$n:$ha2")
        return "Digest username=\"$USER\", realm=\"$r\", nonce=\"$n\", uri=\"$uri\", " +
            "response=\"$response\""
    }

    private fun md5Hex(s: String): String = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun param(header: String, name: String): String? =
        Regex("$name=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)

    private companion object {
        const val USER = "iTunes"
    }
}
