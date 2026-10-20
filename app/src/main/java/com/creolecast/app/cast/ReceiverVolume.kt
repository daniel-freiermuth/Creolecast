package com.creolecast.app.cast

import android.content.Context
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import com.creolecast.app.PacketDirection
import com.creolecast.app.PacketLogger
import com.creolecast.app.PacketType
import io.ktor.websocket.Frame
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.json.JSONObject

/** AirPlay receiver volume runs from -30 dB (quietest) to 0 dB (max). */
private const val DB_RANGE = 30.0
private const val PERCENT_MAX = 100

/** The AirPlay volume value that means mute; -30 dB is still audible. */
private const val AIRPLAY_MUTE_DB = -144.0

/** The AirPlay `volume:` value for [dB] on our -30..0 scale: the bottom means mute. */
internal fun airPlayVolume(dB: Double): Double = if (dB <= -DB_RANGE) AIRPLAY_MUTE_DB else dB

/**
 * The receiver volume, in steps of 1 dB from 0 (muted) to [MAX_STEPS] (0 dB):
 * driven by the notification's buttons and, through a remote-playback MediaSession, by the
 * phone's hardware volume keys, and sent to whichever receivers are connected.
 */
internal class ReceiverVolume(private val context: Context, private val ctx: CastContext) {
    var steps: Int = MAX_STEPS
        private set

    private var mediaSession: MediaSession? = null
    private var volumeProvider: VolumeProvider? = null

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
        val provider = object : VolumeProvider(
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
        }
        session.setPlaybackToRemote(provider)
        session.isActive = true
        mediaSession = session
        volumeProvider = provider
    }

    fun stopSession() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        volumeProvider = null
    }

    /** One step up (+1) or down (-1), from the notification's volume buttons. */
    fun adjust(direction: Int) {
        steps = (steps + direction).coerceIn(0, MAX_STEPS)
        setDb((steps - MAX_STEPS).toDouble())
        ctx.refreshStatus()
    }

    /** Sends an absolute volume in dB (-30.0 muted to 0.0 max) to all active receivers. */
    fun setDb(dB: Double) {
        Log.d(LOG_TAG, "Setting receiver volume to $dB dB")
        val airPlayDb = airPlayVolume(dB)
        ctx.airPlayVolumeDb = airPlayDb
        ctx.scope.launch {
            ctx.raop.sendVolume(airPlayDb)
            ctx.airPlay2.setVolume(airPlayDb)
            ctx.googleCast.setVolume((dB + DB_RANGE) / DB_RANGE)
            sendAriaCastVolume(dB)
        }
    }

    /**
     * A receiver changed its own volume to [level] (0.0 silent to 1.0 max), from its
     * buttons or app: mirrors it on the phone's volume keys without echoing it back.
     */
    fun reflectReceiverLevel(level: Double) {
        val reflected = (level.coerceIn(0.0, 1.0) * MAX_STEPS).roundToInt()
        if (reflected == steps) return
        steps = reflected
        volumeProvider?.currentVolume = reflected
        PacketLogger.log(
            PacketDirection.IN,
            PacketType.CONTROL,
            "Receiver volume changed to $reflected/$MAX_STEPS"
        )
        ctx.refreshStatus()
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
