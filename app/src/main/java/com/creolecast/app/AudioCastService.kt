package com.creolecast.app

import android.app.Service
import android.content.Intent
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.creolecast.app.cast.CastContext
import com.creolecast.app.cast.CastController
import com.creolecast.app.cast.CastNotification
import com.creolecast.app.cast.ReceiverVolume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class CastDestination(
    val name: String,
    val host: String,
    val port: Int,
    val platform: CastPlatform,
    var delayMs: Int = 0,
    val extra: String? = null
)

/**
 * Foreground service that captures what other apps play and casts it to one receiver.
 * It owns the Android lifecycle and the API the UI binds to; the cast itself is run by
 * [CastController] and the protocol sessions in [com.creolecast.app.cast].
 */
class AudioCastService @JvmOverloads constructor(
    /** Dispatcher for all blocking network and audio I/O; injectable for tests. */
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(ioDispatcher + job)

    private lateinit var notification: CastNotification
    private lateinit var volume: ReceiverVolume
    private lateinit var controller: CastController
    private val cast = CastContext(scope, ioDispatcher) { notification.update() }

    val activeDestinations: StateFlow<List<CastDestination>> = cast.destinations.asStateFlow()

    val serverHost: String? get() = activeDestinations.value.firstOrNull()?.host
    val serverName: String?
        get() = if (activeDestinations.value.size > 1) {
            "Multiroom Group"
        } else {
            activeDestinations.value.firstOrNull()?.name
        }
    val serverPort: Int get() = activeDestinations.value.firstOrNull()?.port ?: 0
    val serverPlatform: CastPlatform? get() = activeDestinations.value.firstOrNull()?.platform

    val state: StateFlow<CastState> = cast.state.asStateFlow()
    val stats: StateFlow<CastingStats> = cast.stats.asStateFlow()
    val metadata: StateFlow<TrackMetadata?> = cast.metadata.asStateFlow()
    val pairingPinRequest: StateFlow<String?> = cast.pinPrompt.requests
    val controlCommands: SharedFlow<MediaCommand> = cast.controlCommands.asSharedFlow()
    val audioBufferFlow: SharedFlow<ByteArray> = cast.audio.asSharedFlow()

    private val binder = AudioCastBinder()

    inner class AudioCastBinder : Binder() {
        fun getService(): AudioCastService = this@AudioCastService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val securePrefs = getSharedPreferences(SECURE_PREFS_NAME, MODE_PRIVATE)
        volume = ReceiverVolume(this, cast)
        notification = CastNotification(this, cast, volume)
        controller = CastController(this, cast, prefs, securePrefs, notification, volume)

        // Register route discovery so the system output picker shows MediaRoute2
        // routes when targeting our package's MediaSession.  Without this, the
        // picker has no discovery preferences for us and shows nothing.
        MediaRouter2.getInstance(this).registerRouteCallback(
            mainExecutor,
            object : MediaRouter2.RouteCallback() {},
            RouteDiscoveryPreference.Builder(
                listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO, MediaRoute2Info.FEATURE_REMOTE_PLAYBACK),
                true // activeScan
            ).build()
        )

        controller.metadata.startWorker()
        controller.startArtworkServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        when (intent?.action) {
            ACTION_START -> {
                controller.stop()
                val mediaProjectionToken = intent.getParcelableExtra(
                    EXTRA_MEDIA_PROJECTION_TOKEN,
                    Intent::class.java
                )
                val destination = parseDestination(intent)
                if (mediaProjectionToken != null && destination != null) {
                    controller.start(mediaProjectionToken, destination)
                }
                START_STICKY
            }

            ACTION_STOP -> {
                controller.stopAndExit()
                START_NOT_STICKY
            }

            ACTION_VOLUME_UP -> {
                volume.adjust(1)
                START_STICKY
            }

            ACTION_VOLUME_DOWN -> {
                volume.adjust(-1)
                START_STICKY
            }

            else -> START_NOT_STICKY
        }

    private fun parseDestination(intent: Intent): CastDestination? {
        val host = intent.getStringExtra(EXTRA_SERVER_HOST)
        val port = intent.getIntExtra(EXTRA_SERVER_PORT, 0)
        val name = intent.getStringExtra(EXTRA_SERVER_NAME)
        val platformLabel = intent.getStringExtra(EXTRA_SERVER_PLATFORM)
        val platform = CastPlatform.fromLabel(platformLabel)
        if (platform == null) {
            Log.e(TAG, "Rejecting cast request: unknown platform '${platformLabel.orEmpty()}'")
            return null
        }
        val extra = intent.getStringExtra(EXTRA_SERVER_EXTRA)
        return if (host != null && port != 0 && name != null) {
            CastDestination(name, host, port, platform, extra = extra)
        } else if (platform == CastPlatform.DLNA && host != null && name != null) {
            CastDestination(name, host, 0, platform, extra = extra)
        } else {
            null
        }
    }

    /** Send an absolute volume in dB to all active receivers.
     *  AirPlay range: -30.0 (silent) to 0.0 (max). */
    internal fun sendVolumeDb(dB: Double) = volume.setDb(dB)

    fun setDelay(host: String, delayMs: Int) {
        val current = cast.destinations.value.toMutableList()
        val index = current.indexOfFirst { it.host == host }
        if (index != -1) {
            current[index] = current[index].copy(delayMs = delayMs)
            cast.destinations.value = current
        }
    }

    fun setArtwork(bytes: ByteArray?) {
        cast.artworkBytes = bytes
    }

    fun sendMetadata(metadata: TrackMetadata) = controller.metadata.submit(metadata)

    fun submitPairingPin(host: String, pin: String) {
        Log.d(TAG, "Pairing PIN entered for $host")
        cast.pinPrompt.submit(pin)
    }

    fun resetPairingPinRequest() = cast.pinPrompt.cancel()

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    companion object {
        private const val TAG = "AudioCastService"
        const val ACTION_START = "com.creolecast.app.ACTION_START"
        const val ACTION_STOP = "com.creolecast.app.ACTION_STOP"
        const val ACTION_VOLUME_UP = "com.creolecast.app.ACTION_VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "com.creolecast.app.ACTION_VOLUME_DOWN"
        const val EXTRA_MEDIA_PROJECTION_TOKEN = "com.creolecast.app.EXTRA_MEDIA_PROJECTION_TOKEN"
        const val EXTRA_SERVER_HOST = "com.creolecast.app.EXTRA_SERVER_HOST"
        const val EXTRA_SERVER_PORT = "com.creolecast.app.EXTRA_SERVER_PORT"
        const val EXTRA_SERVER_NAME = "com.creolecast.app.EXTRA_SERVER_NAME"
        const val EXTRA_SERVER_PLATFORM = "com.creolecast.app.EXTRA_SERVER_PLATFORM"
        const val EXTRA_SERVER_EXTRA = "com.creolecast.app.EXTRA_SERVER_EXTRA"

        const val PREFS_NAME = "CreoleCastPrefs"

        // AirPlay 2 pairing PINs live in a separate prefs file, excluded from Android
        // backup/device-transfer (see data_extraction_rules.xml / backup_rules.xml) -
        // they shouldn't end up in a cloud backup or a new device's transfer alongside
        // the rest of the app's ordinary settings.
        const val SECURE_PREFS_NAME = "CreoleCastSecurePrefs"
        const val KEY_LAST_SERVER_HOST = "last_server_host"
        const val KEY_LAST_SERVER_PORT = "last_server_port"
        const val KEY_LAST_SERVER_NAME = "last_server_name"
        const val KEY_LAST_SERVER_PLATFORM = "last_server_platform"

        const val SAMPLE_RATE = 48000
        const val FRAME_SIZE = 3840
        const val LATENCY = 66150

        /** RAOP and AirPlay 2 (ALAC/44100/16/2) stream 44.1 kHz PCM. */
        private const val AIRPLAY_SAMPLE_RATE = 44100

        /**
         * PCM rate the recorder is opened with for [platform].
         *
         * AirPlay 1 (RAOP) requires 44100 Hz — shairport-sync ignores SDP sample rate.
         * AirPlay 2 streams ALAC/44100/16/2 (audioFormat 0x40000) with 44.1 kHz
         * latency and RTP timestamps, so it has to be fed 44100 Hz PCM as well.
         * Android's AudioFlinger resamples internally when the capture rate
         * differs from the source, so this is transparent and correct.
         */
        internal fun captureSampleRate(platform: CastPlatform): Int = when (platform) {
            CastPlatform.AIRPLAY, CastPlatform.AIRPLAY2 -> AIRPLAY_SAMPLE_RATE

            CastPlatform.DLNA, CastPlatform.GOOGLE_CAST, CastPlatform.SNAPCAST,
            CastPlatform.ARIACAST, CastPlatform.MANUAL -> SAMPLE_RATE
        }
    }
}
