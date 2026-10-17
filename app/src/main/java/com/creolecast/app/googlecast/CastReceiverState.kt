package com.creolecast.app.googlecast

import com.creolecast.app.MediaCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

private val USER_STOP_IDLE_REASONS = setOf("CANCELLED", "INTERRUPTED")

/**
 * What a [CastClient] knows about the device, kept current from the RECEIVER_STATUS,
 * MEDIA_STATUS and connection CLOSE messages it pushes; changes the device made on its
 * own are reported to [listener].
 */
internal class CastReceiverState(private val listener: CastListener) {
    /** Virtual-connection endpoint of our receiver app, once launched. */
    @Volatile var transportId: String? = null

    /** Our receiver app's session; null once it was stopped or replaced. */
    @Volatile var appSessionId: String? = null

    @Volatile var mediaSessionId: Int? = null
        private set

    @Volatile var currentItemId: Int? = null
        private set

    @Volatile var volumeLevel = -1.0

    @Volatile var muted = false

    /** Set while we expect a state change we caused, so it isn't reported as remote. */
    @Volatile var expectedPlayerState: String? = null

    @Volatile private var playerState: String? = null

    fun handle(message: CastMessage, payload: JsonObject) {
        val type = payload.string("type")
        when (message.namespace) {
            NS_CONNECTION -> if (type == "CLOSE" && message.sourceId == transportId) {
                transportId = null
                listener.onDisconnected(CastDisconnect.AppReplaced)
            }

            NS_RECEIVER -> if (type == "RECEIVER_STATUS") {
                (payload["status"] as? JsonObject)?.let(::onReceiverStatus)
            }

            NS_MEDIA -> if (type == "MEDIA_STATUS") {
                val status = (payload["status"] as? JsonArray)?.firstOrNull() as? JsonObject
                status?.let(::onMediaStatus)
            }
        }
    }

    private fun onReceiverStatus(status: JsonObject) {
        (status["volume"] as? JsonObject)?.let { volume ->
            val level = (volume["level"] as? JsonPrimitive)?.doubleOrNull
            val mute = (volume["muted"] as? JsonPrimitive)?.booleanOrNull ?: false
            // Reported on connect too, so the phone starts from the speaker's actual level.
            if (level != null && (level != volumeLevel || mute != muted)) {
                volumeLevel = level
                muted = mute
                listener.onVolume(level, mute)
            }
        }
        val sessionId = appSessionId ?: return
        if (findApp(status, DEFAULT_MEDIA_RECEIVER)?.string("sessionId") != sessionId) {
            // Someone launched another app (or stopped ours) on the device.
            appSessionId = null
            transportId = null
            listener.onDisconnected(CastDisconnect.AppReplaced)
        }
    }

    private fun onMediaStatus(status: JsonObject) {
        (status["mediaSessionId"] as? JsonPrimitive)?.intOrNull?.let { mediaSessionId = it }
        (status["currentItemId"] as? JsonPrimitive)?.intOrNull?.let { currentItemId = it }
        val state = status.string("playerState") ?: return
        val previous = playerState
        playerState = state
        val command = if (state == expectedPlayerState) {
            expectedPlayerState = null
            null
        } else {
            remoteCommand(previous, state, status.string("idleReason"))
        }
        command?.let(listener::onRemoteCommand)
    }
}

/** The command a device-side player state change from [previous] to [state] stands for. */
private fun remoteCommand(previous: String?, state: String, idleReason: String?) = when {
    previous == null || previous == state -> null
    state == "PAUSED" -> MediaCommand.PAUSE
    state == "PLAYING" && previous == "PAUSED" -> MediaCommand.PLAY
    state == "IDLE" && idleReason in USER_STOP_IDLE_REASONS -> MediaCommand.STOP
    else -> null
}

/** [appId]'s entry in a receiver `status` object's `applications`, if it is running. */
internal fun findApp(status: JsonObject?, appId: String): JsonObject? {
    val apps = status?.get("applications") as? JsonArray
    return apps?.firstNotNullOfOrNull { app ->
        (app as? JsonObject)?.takeIf { it.string("appId") == appId }
    }
}
