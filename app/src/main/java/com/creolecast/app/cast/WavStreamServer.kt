package com.creolecast.app.cast

import android.util.Log
import com.creolecast.app.AudioCastService
import java.io.BufferedReader
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Port of the HTTP WAV stream that DLNA and Google Cast receivers pull. */
internal const val STREAM_PORT = 8091

/** Receivers may pause the pull for a while (buffer full) before reading again. */
private const val CLIENT_READ_TIMEOUT_MS = 30000

private const val WAV_HEADER_BYTES = 44
private const val WAV_FMT_CHUNK_BYTES = 16
private const val WAV_FORMAT_PCM: Short = 1
private const val WAV_CHANNELS: Short = 2
private const val WAV_BITS_PER_SAMPLE: Short = 16

/** One 16-bit stereo sample frame. */
private const val WAV_BLOCK_ALIGN: Short = 4

/** Size field of a stream of unknown length. */
private const val WAV_UNKNOWN_SIZE = -1

/**
 * Serves the captured PCM as an endless HTTP WAV stream at
 * `http://<device>:[STREAM_PORT]/stream.wav`, for DLNA and Google Cast receivers.
 */
internal class WavStreamServer(private val ctx: CastContext) {
    private val server = LocalSocketServer("Stream", STREAM_PORT, ctx, ::respond)

    fun start() = server.start()

    fun stop() = server.stop()

    private suspend fun respond(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = CLIENT_READ_TIMEOUT_MS
            val input = client.getInputStream().bufferedReader()
            val output = client.getOutputStream()

            val requestLine = input.readLine() ?: return
            Log.d(LOG_TAG, "Stream request: $requestLine")
            val userAgent = readHeaders(input)["user-agent"].orEmpty()
            output.write(responseHeaders(userAgent).toByteArray())

            if (requestLine.startsWith("HEAD")) {
                output.flush()
            } else {
                // The WAV header goes out once, at the start of the 200 OK body.
                output.write(wavHeader())
                ctx.audio.collect { buffer ->
                    output.write(buffer)
                    output.flush()
                }
            }
        } catch (e: IOException) {
            Log.d(LOG_TAG, "Streaming session ended: ${e.reason}")
        }
    }

    /** Request headers up to the blank line, keyed by lower-case name. */
    private fun readHeaders(input: BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var line = input.readLine()
        while (!line.isNullOrEmpty()) {
            Log.d(LOG_TAG, "  Header: $line")
            val parts = line.split(": ", limit = 2)
            if (parts.size == 2) headers[parts[0].lowercase()] = parts[1]
            line = input.readLine()
        }
        return headers
    }

    /** Apple renderers and libmpv expect the audio/x-wav spelling. */
    private fun responseHeaders(userAgent: String): String {
        val isApple = userAgent.contains("Apple", ignoreCase = true) ||
            userAgent.contains("Darwin", ignoreCase = true) ||
            userAgent.contains("libmpv", ignoreCase = true)
        val contentType = if (isApple) "audio/x-wav" else "audio/wav"
        return "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Server: AirTunes/220.68\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
            "Pragma: no-cache\r\n" +
            "Expires: 0\r\n" +
            "ICY-NAME: CreoleCast Stream\r\n" +
            "ICY-METADATA: 0\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n"
    }

    /** RIFF/WAVE header for endless 16-bit stereo PCM at [AudioCastService.SAMPLE_RATE]. */
    private fun wavHeader(): ByteArray = ByteBuffer.allocate(WAV_HEADER_BYTES).apply {
        order(ByteOrder.LITTLE_ENDIAN)
        put("RIFF".toByteArray())
        putInt(WAV_UNKNOWN_SIZE)
        put("WAVE".toByteArray())
        put("fmt ".toByteArray())
        putInt(WAV_FMT_CHUNK_BYTES)
        putShort(WAV_FORMAT_PCM)
        putShort(WAV_CHANNELS)
        putInt(AudioCastService.SAMPLE_RATE)
        putInt(AudioCastService.SAMPLE_RATE * WAV_BLOCK_ALIGN)
        putShort(WAV_BLOCK_ALIGN)
        putShort(WAV_BITS_PER_SAMPLE)
        put("data".toByteArray())
        putInt(WAV_UNKNOWN_SIZE)
    }.array()
}
