package com.creolecast.app.cast

import android.util.Log
import com.creolecast.app.CastDestination
import com.creolecast.app.MediaCommand
import com.creolecast.app.PacketDirection
import com.creolecast.app.PacketLogger
import com.creolecast.app.PacketType
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

private const val RECONNECT_INITIAL_BACKOFF_MS = 1000L

/** The audio channel's backoff doubles per failed attempt, up to 2^5 times the initial one. */
private const val MAX_BACKOFF_EXPONENT = 5.0

/** Receivers other than AriaCast greet with a text frame within this time. */
private const val HANDSHAKE_TIMEOUT_MS = 3000L

/** A stats channel silent for this long is reconnected. */
private const val STATS_TIMEOUT_MS = 10000L

/** Each captured frame is 20 ms of audio. */
private const val FRAME_DURATION_MS = 20

/** Frames sent per captured frame while draining the sync-delay queue. */
private const val MAX_SENDS_PER_CAPTURE = 2

/** The receiver's three WebSocket endpoints. */
private enum class WsChannel(val label: String, val path: String, val packetType: PacketType) {
    AUDIO("Audio", "/audio", PacketType.HANDSHAKE),
    CONTROL("Control", "/control", PacketType.CONTROL),
    STATS("Stats", "/stats", PacketType.STATS)
}

/**
 * A cast to an AriaCast (or compatible) receiver over three WebSocket channels: PCM
 * frames on `/audio` (held back by the destination's sync delay), media commands from
 * the receiver on `/control` and buffer statistics on `/stats`. Each channel reconnects
 * on its own until the cast is cancelled.
 */
internal class AriaCastSession(private val ctx: CastContext, private val dest: CastDestination) {
    private var sentFrames = 0L
    private var audioReconnectAttempts = 0

    suspend fun runAudio() {
        val exponentialBackoff = {
            audioReconnectAttempts++
            val exponent = audioReconnectAttempts.toDouble().coerceAtMost(MAX_BACKOFF_EXPONENT)
            (RECONNECT_INITIAL_BACKOFF_MS * 2.0.pow(exponent)).toLong()
        }
        keepConnected(WsChannel.AUDIO, exponentialBackoff) { session ->
            audioReconnectAttempts = 0
            PacketLogger.log(
                PacketDirection.OUT,
                PacketType.HANDSHAKE,
                "Audio socket connected to ${dest.name} (${dest.host}:${dest.port})"
            )
            if (dest.platform == "AriaCast" || awaitHandshake(session)) {
                ctx.markCasting()
                streamAudio(session)
            }
        }
    }

    suspend fun runControl() {
        val forgetSession = {
            ctx.ariaCastControlSessions.remove(dest.host)
            RECONNECT_INITIAL_BACKOFF_MS
        }
        keepConnected(WsChannel.CONTROL, forgetSession) { session ->
            ctx.ariaCastControlSessions[dest.host] = session
            PacketLogger.log(
                PacketDirection.OUT,
                PacketType.CONTROL,
                "Control socket connected to ${dest.name}"
            )
            for (frame in session.incoming) {
                val command = if (frame is Frame.Text) parseCommand(frame.readText()) else null
                if (command != null) ctx.controlCommands.emit(command)
            }
        }
    }

    suspend fun runStats() {
        keepConnected(WsChannel.STATS, { RECONNECT_INITIAL_BACKOFF_MS }) { session ->
            PacketLogger.log(
                PacketDirection.OUT,
                PacketType.STATS,
                "Stats socket connected to ${dest.name}"
            )
            receiveStats(session)
        }
    }

    /**
     * Keeps [channel] connected until the coroutine is cancelled: runs [session] on each
     * connection, and after a failure logs it and waits for the delay [onFailure] returns.
     *
     * This is the channel's reconnect boundary. Ktor surfaces a failed or dropped WebSocket
     * as many exception types (I/O, protocol violations, closed channels, JSON from the
     * receiver), and every one of them must lead to a reconnect rather than end the cast
     * or crash the service. Cancellation is propagated.
     */
    @Suppress("TooGenericExceptionCaught") // Reconnect boundary, see KDoc.
    private suspend fun keepConnected(
        channel: WsChannel,
        onFailure: () -> Long,
        session: suspend (DefaultClientWebSocketSession) -> Unit
    ) {
        while (currentCoroutineContext().isActive) {
            try {
                ctx.client.webSocket(host = dest.host, port = dest.port, path = channel.path) {
                    session(this)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    LOG_TAG,
                    "${channel.label} WS connection to ${dest.host}:${dest.port} failed: " +
                        e.reason,
                    e
                )
                PacketLogger.log(
                    PacketDirection.IN,
                    channel.packetType,
                    "${channel.label} connection to ${dest.name} failed: ${e.reason}"
                )
                delay(onFailure())
            }
        }
    }

    /** Waits for the receiver's greeting; false (and the connection is retried) without it. */
    private suspend fun awaitHandshake(session: DefaultClientWebSocketSession): Boolean {
        val received =
            withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { session.incoming.receiveCatching() }
        val frame = received?.getOrNull()
        if (frame == null) {
            val reason = if (received == null) {
                "no greeting within $HANDSHAKE_TIMEOUT_MS ms"
            } else {
                "connection closed: ${received.exceptionOrNull()?.reason ?: "no reason"}"
            }
            Log.w(LOG_TAG, "Audio handshake timed out for ${dest.host}: $reason")
            PacketLogger.log(
                PacketDirection.IN,
                PacketType.HANDSHAKE,
                "Handshake timed out for ${dest.name}: $reason"
            )
        } else if (frame !is Frame.Text) {
            PacketLogger.log(
                PacketDirection.IN,
                PacketType.HANDSHAKE,
                "Unexpected handshake frame type from ${dest.name}"
            )
        }
        return frame is Frame.Text
    }

    /**
     * Sends captured frames, holding back as many as the destination's sync delay
     * covers; the delay can change while streaming (sync slider).
     */
    private suspend fun streamAudio(session: DefaultClientWebSocketSession) {
        val delayQueue = ArrayDeque<ByteArray>()
        ctx.audio.collect { buffer ->
            delayQueue.addLast(buffer)
            val currentDelay = ctx.destinations.value.find { it.host == dest.host }?.delayMs ?: 0
            val requiredFrames = currentDelay / FRAME_DURATION_MS
            var sendCount = 0
            while (delayQueue.size > requiredFrames && sendCount < MAX_SENDS_PER_CAPTURE) {
                sendAudioFrame(session, delayQueue.removeFirst())
                sentFrames++
                sendCount++
            }
            ctx.stats.value = ctx.stats.value.copy(sentFrames = sentFrames)
        }
    }

    private fun sendAudioFrame(session: DefaultClientWebSocketSession, buffer: ByteArray) {
        val sent = session.outgoing.trySendBlocking(Frame.Binary(true, buffer))
        if (sent.isSuccess) {
            PacketLogger.log(PacketDirection.OUT, PacketType.AUDIO, "Audio frame sent", buffer.size)
        } else {
            val stats = ctx.stats.value
            ctx.stats.value = stats.copy(droppedFrames = stats.droppedFrames + 1)
            Log.w(LOG_TAG, "Audio frame send failed (dropped)")
            PacketLogger.log(
                PacketDirection.OUT,
                PacketType.AUDIO,
                "Audio frame dropped (send failed)",
                buffer.size
            )
        }
    }

    private fun parseCommand(text: String): MediaCommand? = try {
        val action = JSONObject(text).getString("action")
        MediaCommand.entries.find { it.name.equals(action, ignoreCase = true) }
    } catch (e: JSONException) {
        Log.d(LOG_TAG, "Ignoring malformed control message: ${e.reason}")
        null
    }

    private suspend fun receiveStats(session: DefaultClientWebSocketSession) {
        while (session.isActive) {
            val frame = withTimeoutOrNull(STATS_TIMEOUT_MS) { session.incoming.receive() }
            if (frame == null) return
            if (frame is Frame.Text) {
                val json = JSONObject(frame.readText())
                ctx.stats.value = ctx.stats.value.copy(
                    bufferedFrames = json.optInt("bufferedFrames"),
                    receivedFrames = json.optInt("receivedFrames")
                )
                ctx.refreshStatus()
            }
        }
    }
}
