package com.creolecast.app.googlecast

import com.creolecast.app.MediaCommand
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Cast's Default Media Receiver: plays a media URL, shows MusicTrack metadata. */
internal const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"

/** Default CastV2 port; `_googlecast._tcp` advertises it too. */
internal const val CAST_PORT = 8009

private const val METADATA_TYPE_MUSIC_TRACK = 3
private const val LAUNCH_POLL_ATTEMPTS = 10
private const val LAUNCH_POLL_INTERVAL_MS = 500L
private val LOAD_ERRORS = setOf("LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST")

/** Now-playing info shown on the Cast device. */
internal data class CastMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val imageUrl: String?
)

/** Device-side events of a [CastClient]; all run on its reader thread. */
internal interface CastListener {
    /** Device volume changed (0.0-1.0), from the Home app or the speaker's buttons. */
    fun onVolume(level: Double, muted: Boolean) = Unit

    /** Playback was controlled on the device: PLAY, PAUSE or STOP. */
    fun onRemoteCommand(command: MediaCommand) = Unit

    /** The connection, or our receiver app on the device, went away. */
    fun onDisconnected(reason: CastDisconnect) = Unit
}

/** Why a [CastClient] session ended without being stopped by us. */
internal sealed interface CastDisconnect {
    /** Network failure or heartbeat timeout; worth reconnecting. */
    data class ConnectionLost(val reason: String) : CastDisconnect

    /** The user switched the device to something else; don't fight them for it. */
    data object AppReplaced : CastDisconnect
}

/**
 * Google Cast (CastV2) sender: launches or joins the Default Media Receiver and has it play
 * a live stream that the device pulls over HTTP from the app. On top of [CastChannel]:
 *
 *  - tp.connection - CONNECT/CLOSE virtual connections (to `receiver-0` and to the app)
 *  - receiver      - LAUNCH / STOP the app, GET_STATUS, SET_VOLUME, RECEIVER_STATUS
 *  - media         - LOAD the stream, QUEUE_UPDATE metadata, STOP, MEDIA_STATUS
 *
 * Blocking; call from an I/O thread.
 */
internal class CastClient(
    host: String,
    port: Int = CAST_PORT,
    listener: CastListener,
    /** Lets tests talk to a fake receiver without TLS. */
    useTls: Boolean = true
) {
    private val state = CastReceiverState(listener)
    private val channel = CastChannel(host, port, useTls, state::handle) { reason ->
        listener.onDisconnected(CastDisconnect.ConnectionLost(reason))
    }

    val transportId: String? get() = state.transportId
    val mediaSessionId: Int? get() = state.mediaSessionId
    val muted: Boolean get() = state.muted

    @Volatile private var loaded: Pair<String, String>? = null

    fun connect() {
        channel.open()
        channel.send(RECEIVER_ID, NS_CONNECTION, connectPayload())
    }

    /** Launches (or joins an already running) [appId] and opens a connection to it. */
    fun launch(appId: String = DEFAULT_MEDIA_RECEIVER) {
        var app = findApp(getStatus()["status"] as? JsonObject, appId)
        if (app == null) {
            val reply = channel.request(
                RECEIVER_ID,
                NS_RECEIVER,
                buildJsonObject {
                    put("type", "LAUNCH")
                    put("appId", appId)
                }
            )
            if (reply.string("type") == "LAUNCH_ERROR") {
                throw IOException("launch failed: ${reply.string("reason").orEmpty()}")
            }
            app = findApp(reply["status"] as? JsonObject, appId)
            // Some devices answer before the app is up.
            var attempts = 0
            while (app == null && attempts++ < LAUNCH_POLL_ATTEMPTS) {
                Thread.sleep(LAUNCH_POLL_INTERVAL_MS)
                app = findApp(getStatus()["status"] as? JsonObject, appId)
            }
        }
        val transport = app?.string("transportId")
            ?: throw IOException("receiver app $appId did not start")
        state.transportId = transport
        state.appSessionId = app.string("sessionId")
        channel.send(transport, NS_CONNECTION, connectPayload())
    }

    /** Starts playback of [url]; returns once the device accepted it. */
    fun load(url: String, contentType: String, metadata: CastMetadata?) {
        val transport = state.transportId ?: throw IOException("no receiver app")
        loaded = url to contentType
        state.expectedPlayerState = "PLAYING"
        val reply = channel.request(
            transport,
            NS_MEDIA,
            buildJsonObject {
                put("type", "LOAD")
                put("media", mediaInfo(url, contentType, metadata))
                put("autoplay", true)
                put("currentTime", 0)
            }
        )
        val type = reply.string("type").orEmpty()
        if (type in LOAD_ERRORS) {
            throw IOException("LOAD rejected: $type ${reply.string("reason").orEmpty()}")
        }
    }

    /** Replaces the displayed metadata of the running live stream without reloading it. */
    fun updateMetadata(metadata: CastMetadata) {
        val transport = state.transportId
        val session = state.mediaSessionId
        val (url, contentType) = loaded ?: return
        if (transport == null || session == null) return
        val item = state.currentItemId
        channel.sendRequest(
            transport,
            NS_MEDIA,
            buildJsonObject {
                put("type", "QUEUE_UPDATE")
                put("mediaSessionId", session)
                if (item != null) {
                    put(
                        "items",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", item)
                                    put("media", mediaInfo(url, contentType, metadata))
                                }
                            )
                        }
                    )
                }
            }
        )
    }

    fun setVolume(level: Double) {
        val clamped = level.coerceIn(0.0, 1.0)
        state.volumeLevel = clamped
        sendVolume(buildJsonObject { put("level", clamped) })
    }

    fun setMuted(mute: Boolean) {
        state.muted = mute
        sendVolume(buildJsonObject { put("muted", mute) })
    }

    /** Stops playback and our receiver app, leaving the device idle. */
    fun stop() {
        val transport = state.transportId
        val session = state.mediaSessionId
        if (transport != null && session != null) {
            state.expectedPlayerState = "IDLE"
            channel.sendRequest(
                transport,
                NS_MEDIA,
                buildJsonObject {
                    put("type", "STOP")
                    put("mediaSessionId", session)
                }
            )
        }
        state.appSessionId?.let { sessionId ->
            state.appSessionId = null
            channel.sendRequest(
                RECEIVER_ID,
                NS_RECEIVER,
                buildJsonObject {
                    put("type", "STOP")
                    put("sessionId", sessionId)
                }
            )
        }
        transport?.let { channel.send(it, NS_CONNECTION, typed("CLOSE")) }
        channel.send(RECEIVER_ID, NS_CONNECTION, typed("CLOSE"))
    }

    fun close() = channel.close()

    private fun getStatus() = channel.request(RECEIVER_ID, NS_RECEIVER, typed("GET_STATUS"))

    private fun sendVolume(volume: JsonObject) {
        channel.sendRequest(
            RECEIVER_ID,
            NS_RECEIVER,
            buildJsonObject {
                put("type", "SET_VOLUME")
                put("volume", volume)
            }
        )
    }
}

private fun connectPayload() = buildJsonObject {
    put("type", "CONNECT")
    put("origin", JsonObject(emptyMap()))
}

private fun mediaInfo(url: String, contentType: String, metadata: CastMetadata?) = buildJsonObject {
    put("contentId", url)
    put("contentUrl", url)
    put("contentType", contentType)
    put("streamType", "LIVE")
    put(
        "metadata",
        buildJsonObject {
            put("metadataType", METADATA_TYPE_MUSIC_TRACK)
            put("title", metadata?.title ?: "CreoleCast")
            metadata?.artist?.let { put("artist", it) }
            metadata?.album?.let { put("albumName", it) }
            metadata?.imageUrl?.let { imageUrl ->
                put("images", buildJsonArray { add(buildJsonObject { put("url", imageUrl) }) })
            }
        }
    )
}
