package com.creolecast.app.cast

import android.util.Log
import java.io.IOException
import java.net.Socket

/** Port of the HTTP artwork server receivers fetch album art from. */
internal const val ARTWORK_PORT = 8090

private const val CLIENT_READ_TIMEOUT_MS = 10000

/**
 * Serves the current track's artwork ([CastContext.artworkBytes]) as a JPEG at
 * `http://<device>:[ARTWORK_PORT]/artwork.jpg`, the URL put into receiver metadata.
 */
internal class ArtworkServer(private val ctx: CastContext) {
    private val server = LocalSocketServer("Artwork", ARTWORK_PORT, ctx, ::respond)

    fun start() = server.start()

    fun stop() = server.stop()

    private fun respond(client: Socket) {
        try {
            client.soTimeout = CLIENT_READ_TIMEOUT_MS
            val request = client.getInputStream().bufferedReader().readLine()
            Log.d(LOG_TAG, "Artwork request: $request")
            val output = client.getOutputStream()
            val bytes = ctx.artworkBytes
            if (bytes != null) {
                output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                output.write("Content-Type: image/jpeg\r\n".toByteArray())
                output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                output.write("Cache-Control: no-cache\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
                output.write(bytes)
            } else {
                output.write("HTTP/1.1 404 Not Found\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
            }
            output.flush()
        } catch (e: IOException) {
            Log.d(LOG_TAG, "Artwork request failed: ${e.reason}")
        }
    }
}
