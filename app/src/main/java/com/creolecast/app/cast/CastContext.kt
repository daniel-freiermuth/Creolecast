package com.creolecast.app.cast

import android.util.Log
import com.creolecast.app.CastDestination
import com.creolecast.app.CastState
import com.creolecast.app.CastingStats
import com.creolecast.app.MediaCommand
import com.creolecast.app.PacketDirection
import com.creolecast.app.PacketLogger
import com.creolecast.app.PacketType
import com.creolecast.app.TrackMetadata
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Log tag shared by the service and all of its cast collaborators. */
internal const val LOG_TAG = "AudioCastService"

/**
 * State shared by [com.creolecast.app.AudioCastService] and the protocol sessions of the
 * current cast: the service-lifetime coroutine scope and HTTP client, the observable cast
 * state, the captured PCM stream and the per-receiver registries that volume, metadata
 * and teardown commands are routed through.
 */
internal class CastContext(
    /** Service-lifetime scope; background work that must outlive one cast runs here. */
    val scope: CoroutineScope,
    /** Dispatcher for all blocking network and audio I/O. */
    val ioDispatcher: CoroutineDispatcher,
    /** Refreshes the foreground notification after a status change. */
    private val onStatusChanged: () -> Unit
) {
    val client: HttpClient by lazy(::createCastHttpClient)

    val destinations = MutableStateFlow<List<CastDestination>>(emptyList())
    val state = MutableStateFlow(CastState.OFF)
    val stats = MutableStateFlow(CastingStats())
    val metadata = MutableStateFlow<TrackMetadata?>(null)
    val controlCommands = MutableSharedFlow<MediaCommand>(extraBufferCapacity = 10)

    /** Captured PCM frames, fanned out to whichever protocol session is streaming. */
    val audio = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 500,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val pinPrompt = PairingPinPrompt()

    /** JPEG artwork of the current track, served by [ArtworkServer] and sent to AirPlay 2. */
    var artworkBytes: ByteArray? = null

    val raop = RaopRegistry()
    val airPlay2 = AirPlay2Registry()
    val googleCast = GoogleCastRegistry()

    /**
     * The AirPlay `volume:` last chosen ([airPlayVolume]); new RAOP and AirPlay 2 sessions
     * start at it instead of full volume.
     */
    @Volatile var airPlayVolumeDb: Double = 0.0

    // Mutated from independent Dispatchers.IO coroutines (the session, the metadata
    // refresh timer and volume commands run concurrently), so a plain HashMap here
    // would be a real ConcurrentModificationException risk.
    val ariaCastControlSessions: MutableMap<String, DefaultClientWebSocketSession> =
        Collections.synchronizedMap(mutableMapOf())

    /** Marks the cast as streaming and refreshes the notification. */
    fun markCasting() {
        state.value = CastState.CASTING
        onStatusChanged()
    }

    /**
     * Ends a session that cannot start because [reason] (a missing precondition) holds:
     * logs it to logcat and the packet log, sets [CastState.ERROR] and refreshes the
     * notification, so the cast does not sit in CONNECTING without explanation.
     */
    fun failStart(dest: CastDestination, reason: String) {
        val message = "Cannot start ${dest.platform.label} session for ${dest.name}: $reason"
        Log.e(LOG_TAG, message)
        PacketLogger.log(PacketDirection.IN, PacketType.HANDSHAKE, message)
        state.value = CastState.ERROR
        onStatusChanged()
    }

    fun refreshStatus() = onStatusChanged()
}
