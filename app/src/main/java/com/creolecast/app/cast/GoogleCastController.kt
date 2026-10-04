package com.creolecast.app.cast

import android.util.Log
import com.creolecast.app.CastDestination
import com.creolecast.app.CastState
import com.creolecast.app.MediaCommand
import com.creolecast.app.PacketDirection
import com.creolecast.app.PacketLogger
import com.creolecast.app.PacketType
import com.creolecast.app.TrackMetadata
import com.creolecast.app.googlecast.CAST_PORT
import com.creolecast.app.googlecast.CastClient
import com.creolecast.app.googlecast.CastDisconnect
import com.creolecast.app.googlecast.CastListener
import com.creolecast.app.googlecast.CastMetadata
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Consecutive failed connection attempts before the cast is given up. */
private const val MAX_CONNECT_FAILURES = 3
private const val RECONNECT_BACKOFF_MS = 1000L

/**
 * Google Cast over CastV2: launches the Default Media Receiver and has it play our live
 * WAV stream ([WavStreamServer]), with now-playing metadata. Volume and play/pause changes
 * made on the device (Home app, speaker buttons) are mirrored back to the phone; a lost
 * connection is re-established.
 */
internal class GoogleCastController(
    private val ctx: CastContext,
    private val volume: ReceiverVolume
) {
    // Last metadata sent per host, so the periodic refresh doesn't re-send it (Cast
    // devices re-fetch the artwork on every QUEUE_UPDATE).
    private val lastSentMetadata = Collections.synchronizedMap(mutableMapOf<String, CastMetadata>())

    /** Streams to [dest] until the cast is cancelled or the device is taken over. */
    suspend fun cast(dest: CastDestination) = withContext(ctx.ioDispatcher) {
        val myIp = localIpv4Address()
        if (myIp == null) {
            ctx.failStart(dest, NO_LOCAL_ADDRESS)
            return@withContext
        }
        val streamUrl = "http://$myIp:$STREAM_PORT/stream.wav"
        var failures = 0
        while (isActive) {
            val ended = CompletableDeferred<CastDisconnect>()
            val client = newClient(dest, ended)
            ctx.state.value = CastState.CONNECTING
            try {
                client.connect()
                client.launch()
                client.load(streamUrl, "audio/wav", castMetadata(ctx.metadata.value))
            } catch (e: IOException) {
                client.close()
                Log.e(LOG_TAG, "Google Cast session failed for ${dest.name}: ${e.reason}")
                PacketLogger.log(
                    PacketDirection.IN,
                    PacketType.HANDSHAKE,
                    "Google Cast ${dest.name} failed: ${e.reason}"
                )
                if (++failures >= MAX_CONNECT_FAILURES) {
                    ctx.state.value = CastState.ERROR
                    return@withContext
                }
                delay(RECONNECT_BACKOFF_MS shl failures)
                continue
            }
            failures = 0
            PacketLogger.log(
                PacketDirection.OUT,
                PacketType.HANDSHAKE,
                "Google Cast: ${dest.name} playing $streamUrl"
            )
            if (stream(dest, client, ended) == CastDisconnect.AppReplaced) {
                ctx.state.value = CastState.ERROR
                return@withContext
            }
            delay(RECONNECT_BACKOFF_MS)
        }
    }

    /** Sends [metadata] to [dest]'s device unless it already shows it. */
    fun updateMetadata(dest: CastDestination, metadata: TrackMetadata) {
        val client = ctx.googleCast[dest.host] ?: return
        val cast = castMetadata(metadata)
        if (lastSentMetadata[dest.host] == cast) return
        client.updateMetadata(cast)
        lastSentMetadata[dest.host] = cast
        PacketLogger.log(
            PacketDirection.OUT,
            PacketType.METADATA,
            "Metadata sent to ${dest.name} (Google Cast)"
        )
    }

    private fun newClient(dest: CastDestination, ended: CompletableDeferred<CastDisconnect>) =
        CastClient(
            dest.host,
            if (dest.port > 0) dest.port else CAST_PORT,
            object : CastListener {
                override fun onVolume(level: Double, muted: Boolean) =
                    volume.reflectReceiverLevel(if (muted) 0.0 else level)

                override fun onRemoteCommand(command: MediaCommand) {
                    PacketLogger.log(
                        PacketDirection.IN,
                        PacketType.CONTROL,
                        "Remote command from ${dest.name}: $command"
                    )
                    ctx.scope.launch { ctx.controlCommands.emit(command) }
                }

                override fun onDisconnected(reason: CastDisconnect) {
                    ended.complete(reason)
                }
            }
        )

    /** Keeps [client] registered while it plays; returns why the session ended. */
    private suspend fun stream(
        dest: CastDestination,
        client: CastClient,
        ended: CompletableDeferred<CastDisconnect>
    ): CastDisconnect {
        ctx.googleCast.register(dest.host, client)
        lastSentMetadata.remove(dest.host)
        ctx.markCasting()
        try {
            val reason = ended.await()
            Log.w(LOG_TAG, "Google Cast ${dest.name} ended: $reason")
            PacketLogger.log(
                PacketDirection.IN,
                PacketType.HANDSHAKE,
                "Google Cast ${dest.name} ended: $reason"
            )
            return reason
        } finally {
            ctx.googleCast.unregister(dest.host, client)
            // Runs on cancellation too (the user hit Stop): NonCancellable lets the STOP
            // reach the device instead of the connection just being dropped.
            withContext(NonCancellable) {
                bestEffort("Stopping Google Cast ${dest.name}") { client.stop() }
                client.close()
            }
        }
    }

    /**
     * Our own artwork (served by [ArtworkServer]) wins over the source app's URL; its URL
     * carries a content hash because Cast devices cache images by URL.
     */
    private fun castMetadata(metadata: TrackMetadata?): CastMetadata {
        val artwork = ctx.artworkBytes
        val myIp = if (artwork != null) localIpv4Address() else null
        val imageUrl = if (artwork != null && myIp != null) {
            "http://$myIp:$ARTWORK_PORT/artwork.jpg?v=${artwork.contentHashCode().toUInt()}"
        } else {
            metadata?.artworkUrl?.takeIf { it.startsWith("http") }
        }
        return CastMetadata(metadata?.title, metadata?.artist, metadata?.album, imageUrl)
    }
}
