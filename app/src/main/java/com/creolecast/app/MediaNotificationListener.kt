package com.creolecast.app

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// The framework instantiates the service through the no-arg constructor Kotlin generates
// because the only parameter has a default.
class MediaNotificationListener(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
    NotificationListenerService() {

    private var audioCastService: AudioCastService? = null
    private var isBound = false
    private var activeMediaController: MediaController? = null
    private var positionUpdateJob: Job? = null
    private var commandCollectionJob: Job? = null
    private var stateObservationJob: Job? = null
    private val scope = CoroutineScope(ioDispatcher + Job())
    private lateinit var mediaSessionManager: MediaSessionManager

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioCastService.AudioCastBinder
            val boundService = binder.getService()
            audioCastService = boundService
            isBound = true
            Log.d(TAG, "Bound to AudioCastService")

            handleMediaSessionsChanged()
            startCommandCollection(boundService)
            startStateObservation(boundService)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            audioCastService = null
            isBound = false
            commandCollectionJob?.cancel()
            stateObservationJob?.cancel()
            Log.d(TAG, "Unbound from AudioCastService")
        }
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "<no title>"
            Log.d(TAG, "onMetadataChanged: $title")
            syncMetadata()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.d(TAG, "onPlaybackStateChanged: ${state?.state ?: "<no state>"}")
            syncMetadata()
            if (state?.state == PlaybackState.STATE_PLAYING) {
                startPositionUpdates()
            } else {
                positionUpdateJob?.cancel()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        Intent(this, AudioCastService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun startStateObservation(service: AudioCastService) {
        stateObservationJob?.cancel()
        stateObservationJob = scope.launch {
            service.state.collectLatest { state ->
                if (state == CastState.CASTING) {
                    Log.d(TAG, "Service state is CASTING, forcing metadata sync.")
                    syncMetadata()
                }
            }
        }
    }

    private fun startCommandCollection(service: AudioCastService) {
        commandCollectionJob?.cancel()
        commandCollectionJob = scope.launch {
            service.controlCommands.collectLatest { command: MediaCommand ->
                handleIncomingCommand(command)
            }
        }
    }

    private fun handleIncomingCommand(command: MediaCommand) {
        val controller = activeMediaController ?: run {
            Log.w(TAG, "Received command $command but no active media controller found.")
            return
        }

        val transportControls = controller.transportControls
        when (command) {
            MediaCommand.PLAY -> transportControls.play()

            MediaCommand.PAUSE -> transportControls.pause()

            MediaCommand.TOGGLE -> {
                val state = controller.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) {
                    transportControls.pause()
                } else {
                    transportControls.play()
                }
            }

            MediaCommand.NEXT -> transportControls.skipToNext()

            MediaCommand.PREVIOUS -> transportControls.skipToPrevious()

            MediaCommand.STOP -> transportControls.stop()
        }
        Log.d(TAG, "Executed command: $command on ${controller.packageName}")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn?.notification?.category == "transport") {
            handleMediaSessionsChanged()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.notification?.category == "transport") {
            handleMediaSessionsChanged()
        }
    }

    private fun handleMediaSessionsChanged() {
        val mediaControllers = getActiveMediaControllers()
        // Prioritize the one that is currently playing
        val newMediaController =
            mediaControllers.find { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: mediaControllers.firstOrNull()
        val currentController = activeMediaController

        if (newMediaController?.packageName != currentController?.packageName) {
            currentController?.unregisterCallback(mediaControllerCallback)
            activeMediaController = newMediaController
            newMediaController?.registerCallback(mediaControllerCallback)
            Log.d(TAG, "Switched media controller to: ${newMediaController?.packageName ?: "none"}")
            syncMetadata()

            if (newMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                startPositionUpdates()
            } else {
                positionUpdateJob?.cancel()
            }
        } else if (newMediaController == null && currentController != null) {
            currentController.unregisterCallback(mediaControllerCallback)
            activeMediaController = null
            Log.d(TAG, "Cleared media controller")
            syncMetadata()
            positionUpdateJob?.cancel()
        }
    }

    private fun getActiveMediaControllers(): List<MediaController> {
        val componentName = ComponentName(this, MediaNotificationListener::class.java)
        return try {
            mediaSessionManager.getActiveSessions(componentName)
                .filter { it.packageName != packageName }
        } catch (e: SecurityException) {
            // Thrown while notification access is not granted to this listener.
            Log.e(TAG, "Failed to get active media sessions", e)
            emptyList()
        }
    }

    private fun syncMetadata() {
        val controller = activeMediaController
        val service = audioCastService ?: return

        if (controller == null) {
            service.setArtwork(null)
            service.sendMetadata(TrackMetadata(null, null, null, null, null, null, false))
            return
        }

        val metadata = controller.metadata
        val playbackState = controller.playbackState

        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val position = if (isPlaying) {
            val timeDiff = SystemClock.elapsedRealtime() - playbackState.lastPositionUpdateTime
            playbackState.position + (timeDiff * playbackState.playbackSpeed).toLong()
        } else {
            playbackState?.position
        }

        service.setArtwork(readArtwork(metadata, contentResolver))

        val track = TrackMetadata(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            artworkUrl =
                metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            positionMs = position,
            isPlaying = isPlaying
        )

        Log.d(
            TAG,
            "Syncing metadata: ${track.title ?: "<no title>"} - isPlaying=$isPlaying " +
                "@ ${position ?: "<no position>"}"
        )
        service.sendMetadata(track)
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                delay(POSITION_UPDATE_INTERVAL_MS)
                if (activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    syncMetadata()
                } else {
                    break
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
        }
        activeMediaController?.unregisterCallback(mediaControllerCallback)
        positionUpdateJob?.cancel()
        commandCollectionJob?.cancel()
        stateObservationJob?.cancel()
    }

    companion object {
        const val TAG = "MediaNotificationListener"

        private const val POSITION_UPDATE_INTERVAL_MS = 1000L

        fun isEnabled(context: Context): Boolean {
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            val componentName = ComponentName(
                context,
                MediaNotificationListener::class.java
            ).flattenToString()
            return enabledListeners?.contains(componentName) == true
        }
    }
}

private const val ARTWORK_JPEG_QUALITY = 80

/** Artwork bytes: the embedded bitmap as JPEG, else the bytes behind a local artwork URI. */
private fun readArtwork(metadata: MediaMetadata?, contentResolver: ContentResolver): ByteArray? {
    val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
    val uriStr = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)

    return when {
        bitmap != null -> {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, stream)
            stream.toByteArray()
        }

        uriStr != null -> readArtworkUri(uriStr, contentResolver)

        else -> null
    }
}

// The URI points into another app's ContentProvider, which can surface any RuntimeException
// across Binder. Artwork is best-effort and must not crash the listener.
@Suppress("TooGenericExceptionCaught")
private fun readArtworkUri(uriStr: String, contentResolver: ContentResolver): ByteArray? = try {
    val uri = uriStr.toUri()
    if (uri.scheme == "http" || uri.scheme == "https") {
        // Web URLs reach the receiver as artworkUrl; only local (content://) URIs are read.
        null
    } else {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }
} catch (e: Exception) {
    Log.w(MediaNotificationListener.TAG, "Failed to read artwork URI: $uriStr", e)
    null
}
