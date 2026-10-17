package com.creolecast.app.cast

import android.app.Activity
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import androidx.core.content.edit
import com.creolecast.app.AudioCastService
import com.creolecast.app.CastDestination
import com.creolecast.app.CastState
import com.creolecast.app.CastingStats
import com.creolecast.app.PacketDirection
import com.creolecast.app.PacketLogger
import com.creolecast.app.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long TEARDOWN/stop requests get to reach the receivers before their sockets close. */
private const val TEARDOWN_GRACE_MS = 2000L

/**
 * Runs one cast at a time for [AudioCastService]: foreground state, wake lock, phone
 * volume, MediaProjection capture, the local HTTP servers and the protocol sessions, and
 * tears all of it down again.
 */
internal class CastController(
    private val service: Service,
    private val ctx: CastContext,
    private val prefs: SharedPreferences,
    securePrefs: SharedPreferences,
    private val notification: CastNotification,
    private val volume: ReceiverVolume
) {
    private val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val projectionManager =
        service.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val wakeLock = CastWakeLock(service)
    private val capture = AudioCapture(ctx)
    private val streamServer = WavStreamServer(ctx)
    private val artworkServer = ArtworkServer(ctx)
    private val receivers =
        ReceiverProtocols(ctx, AirPlayIdentity(prefs), securePrefs, service, volume)
    val metadata = MetadataPublisher(ctx, receivers.dlna, receivers.googleCast)

    private var sessionJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var originalVolume = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Only react when the projection was stopped from outside, not by stop().
            if (mediaProjection != null) stopAndExit()
        }
    }

    fun startArtworkServer() = artworkServer.start()

    /** Starts casting to [destination] with the MediaProjection grant in [projectionToken]. */
    fun start(projectionToken: Intent, destination: CastDestination) {
        sessionJob?.cancel()

        ctx.destinations.value = listOf(destination)
        ctx.state.value = CastState.CONNECTING
        val job = SupervisorJob()
        sessionJob = job
        val sessionScope = CoroutineScope(ctx.ioDispatcher + job)

        // The phone itself stays silent while its audio plays on the receiver.
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        rememberDestination(destination)

        if (!enterForeground()) return
        wakeLock.acquire()
        if (destination.platform.supportsVolumeSession) volume.startSession()

        // The MediaProjection token is single-use on Android 14+; obtain it once, before
        // any retries.
        val projection = openProjection(projectionToken) ?: return
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        sessionScope.launch { runCast(this, destination, projection) }
    }

    /** Ends the cast: receivers are told to stop and every local resource is released. */
    fun stop() {
        val teardownJobs = receivers.stopAll(ctx.destinations.value.toList())
        wakeLock.release()
        volume.stopSession()

        // Restore the volume only if the user didn't change it during casting.
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }

        releaseCaptureAndServers()
        ctx.ariaCastControlSessions.clear()
        closeAfterTeardown(teardownJobs)

        ctx.destinations.value = emptyList()
        sessionJob?.cancel()
        sessionJob = null
        ctx.state.value = CastState.OFF
        ctx.stats.value = CastingStats()
    }

    /** Stops the cast and the service with it. */
    fun stopAndExit() {
        stop()
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        service.stopSelf()
    }

    private fun rememberDestination(destination: CastDestination) {
        prefs.edit {
            putString(AudioCastService.KEY_LAST_SERVER_HOST, destination.host)
            putInt(AudioCastService.KEY_LAST_SERVER_PORT, destination.port)
            putString(AudioCastService.KEY_LAST_SERVER_NAME, destination.name)
            putString(AudioCastService.KEY_LAST_SERVER_PLATFORM, destination.platform.label)
        }
    }

    private fun enterForeground(): Boolean = try {
        service.startForeground(
            CastNotification.ID,
            notification.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        true
    } catch (e: ForegroundServiceStartNotAllowedException) {
        Log.e(LOG_TAG, "Foreground service start not allowed", e)
        PacketLogger.log(
            PacketDirection.IN,
            PacketType.HANDSHAKE,
            "Foreground service start not allowed: ${e.reason}"
        )
        ctx.state.value = CastState.ERROR
        false
    }

    /** The granted projection, or null after undoing [start]'s setup when it was refused. */
    private fun openProjection(projectionToken: Intent): MediaProjection? {
        val projection = projectionManager.getMediaProjection(Activity.RESULT_OK, projectionToken)
        if (projection == null) {
            Log.e(LOG_TAG, "MediaProjection is null")
            PacketLogger.log(
                PacketDirection.IN,
                PacketType.HANDSHAKE,
                "MediaProjection permission was not granted"
            )
            volume.stopSession()
            wakeLock.release()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            sessionJob?.cancel()
            sessionJob = null
            ctx.state.value = CastState.ERROR
        }
        return projection
    }

    /** The cast itself, in [scope]: capture, local servers, protocol sessions, metadata. */
    private suspend fun runCast(
        scope: CoroutineScope,
        destination: CastDestination,
        projection: MediaProjection
    ) {
        val sampleRate = AudioCastService.captureSampleRate(destination.platform)
        if (!capture.open(projection, sampleRate)) {
            ctx.state.value = CastState.ERROR
            return
        }
        if (destination.platform.pullsStreamOverHttp) {
            streamServer.start()
            artworkServer.start()
        }
        scope.launch { capture.pump() }
        receivers.launch(scope, destination)
        metadata.launchRefreshLoop(scope)
        ctx.metadata.value?.let { metadata.submit(it) }
    }

    private fun releaseCaptureAndServers() {
        capture.release()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        streamServer.stop()
        artworkServer.stop()
    }

    /**
     * Closes the RAOP sockets, AirPlay 2 and Google Cast clients only after the
     * TEARDOWN/stop requests [teardownJobs] are sending have had a bounded window to reach
     * the receivers; closing them at once meant the requests almost always lost that race
     * and receivers held the session until their own timeout. The references are taken
     * now, so a fast reconnect that reuses the registries in the meantime isn't affected.
     */
    private fun closeAfterTeardown(teardownJobs: List<Job>) {
        val raopSockets = ctx.raop.snapshotSockets()
        val airPlay2Clients = ctx.airPlay2.snapshot()
        val googleCastClients = ctx.googleCast.snapshot()
        ctx.raop.clear()
        ctx.airPlay2.clear()
        ctx.googleCast.clear()
        ctx.scope.launch(ctx.ioDispatcher) {
            withTimeoutOrNull(TEARDOWN_GRACE_MS) { teardownJobs.joinAll() }
            raopSockets.forEach { closeQuietly(it, "RAOP control socket") }
            airPlay2Clients.forEach { client ->
                bestEffort("Closing AirPlay 2 client") { client.close() }
            }
            googleCastClients.forEach { it.close() }
        }
    }
}
