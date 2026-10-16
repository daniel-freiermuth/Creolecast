package com.creolecast.app.cast

import android.content.Context
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import io.ktor.websocket.Frame
import kotlinx.coroutines.launch
import org.json.JSONObject

/** AirPlay receiver volume runs from -30 dB (silent) to 0 dB (max). */
private const val DB_RANGE = 30.0
private const val PERCENT_MAX = 100

/**
 * The receiver volume, in steps of 1 dB from 0 (silent, -30 dB) to [MAX_STEPS] (0 dB):
 * driven by the notification's buttons and, through a remote-playback MediaSession, by the
 * phone's hardware volume keys, and sent to whichever receivers are connected.
 */
internal class ReceiverVolume(private val context: Context, private val ctx: CastContext) {
    var steps: Int = MAX_STEPS
        private set

    private var mediaSession: MediaSession? = null

    /**
     * Starts a MediaSession with a remote VolumeProvider so the phone's hardware volume
     * buttons control the receiver volume when no other media app is competing for
     * priority.
     */
    fun startSession() {
        stopSession()
        val dest = ctx.destinations.value.firstOrNull()
        val session = MediaSession(context, "CreoleCast")
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, dest?.name ?: "AirPlay")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "CreoleCast")
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )
        session.setCallback(object : MediaSession.Callback() {})
        session.setPlaybackToRemote(object : VolumeProvider(
            VOLUME_CONTROL_ABSOLUTE,
            MAX_STEPS,
            steps
        ) {
            override fun onSetVolumeTo(volume: Int) {
                applyVolume(volume)
            }

            override fun onAdjustVolume(direction: Int) {
                applyVolume((currentVolume + direction).coerceIn(0, MAX_STEPS))
            }

            private fun applyVolume(volume: Int) {
                steps = volume
                currentVolume = volume
                setDb((volume - MAX_STEPS).toDouble())
                ctx.refreshStatus()
            }
        })
        session.isActive = true
        mediaSession = session
    }

    fun stopSession() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    /** Back to full volume, where a new AirPlay handshake leaves the receiver. */
    fun reset() {
        steps = MAX_STEPS
    }

    /** One step up (+1) or down (-1), from the notification's volume buttons. */
    fun adjust(direction: Int) {
        steps = (steps + direction).coerceIn(0, MAX_STEPS)
        setDb((steps - MAX_STEPS).toDouble())
        ctx.refreshStatus()
    }

    /** Sends an absolute volume in dB (-30.0 silent to 0.0 max) to all active receivers. */
    fun setDb(dB: Double) {
        Log.d(LOG_TAG, "Setting receiver volume to $dB dB")
        ctx.scope.launch {
            ctx.raop.sendVolume(dB)
            ctx.airPlay2.setVolume(dB)
            sendAriaCastVolume(dB)
        }
    }

    private suspend fun sendAriaCastVolume(dB: Double) {
        val level = ((dB + DB_RANGE) / DB_RANGE * PERCENT_MAX).toInt().coerceIn(0, PERCENT_MAX)
        val command = JSONObject().apply {
            put("command", "volume_set")
            put("level", level)
        }.toString()
        val sessions = synchronized(ctx.ariaCastControlSessions) {
            ctx.ariaCastControlSessions.values.toList()
        }
        sessions.forEach { session ->
            bestEffort("AriaCast volume") { session.send(Frame.Text(command)) }
        }
    }

    companion object {
        const val MAX_STEPS = 30
    }
}
