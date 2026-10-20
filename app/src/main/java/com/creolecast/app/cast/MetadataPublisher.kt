package com.creolecast.app.cast

import com.creolecast.app.CastDestination
import com.creolecast.app.CastPlatform
import com.creolecast.app.PacketDirection
import com.creolecast.app.PacketLogger
import com.creolecast.app.PacketType
import com.creolecast.app.TrackMetadata
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.http.path
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Receivers lose metadata over time (reconnects, app switches); re-send it this often. */
private const val REFRESH_INTERVAL_MS = 10000L
private const val METADATA_TIMEOUT_MS = 5000L

/**
 * Pushes the current track's metadata to the cast's receivers, in each protocol's way:
 * DMAP over RAOP and [com.creolecast.app.airplay2.AirPlay2Client], DIDL-Lite in
 * a DLNA SetAVTransportURI, a CastV2 QUEUE_UPDATE and JSON to AriaCast/Snapcast's
 * `/metadata` endpoint.
 */
internal class MetadataPublisher(
    private val ctx: CastContext,
    private val dlna: DlnaController,
    private val googleCast: GoogleCastController
) {
    private val updates = Channel<TrackMetadata>(Channel.CONFLATED)

    // DLNA only gets a new SetAVTransportURI when title/artist change, per renderer host.
    private val lastSentToDlna =
        Collections.synchronizedMap(mutableMapOf<String, Pair<String?, String?>>())

    /** Publishes each [submit]ted update until the service ends; only the newest is kept. */
    fun startWorker() {
        ctx.scope.launch {
            for (metadata in updates) {
                val wasPlaying = ctx.metadata.value?.isPlaying
                ctx.metadata.value = metadata
                if (wasPlaying == true && !metadata.isPlaying) flushAirPlay()
                publish(metadata)
            }
        }
    }

    /** On pause, AirPlay receivers drop what they buffered instead of playing it out. */
    private suspend fun flushAirPlay() {
        ctx.raop.flushAll()
        ctx.airPlay2.flushAll()
    }

    fun submit(metadata: TrackMetadata) {
        updates.trySend(metadata)
    }

    /** Re-publishes the current metadata periodically for as long as [scope] (the cast) lives. */
    fun launchRefreshLoop(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                ctx.metadata.value?.let { publish(it) }
            }
        }
    }

    private suspend fun publish(metadata: TrackMetadata) {
        val destinations = ctx.destinations.value
        if (destinations.isEmpty()) return

        val published = withArtworkUrl(metadata)
        val key = published.title to published.artist
        destinations.forEach { dest ->
            bestEffort("Metadata push to ${dest.name}") { publishTo(dest, published, key) }
        }
    }

    /** Points artworkUrl at our [ArtworkServer] while there is artwork to serve. */
    private fun withArtworkUrl(metadata: TrackMetadata): TrackMetadata {
        val myIp = if (ctx.artworkBytes != null) localIpv4Address() else null
        return if (myIp != null) {
            metadata.copy(artworkUrl = "http://$myIp:$ARTWORK_PORT/artwork.jpg")
        } else {
            metadata
        }
    }

    private suspend fun publishTo(
        dest: CastDestination,
        metadata: TrackMetadata,
        key: Pair<String?, String?>
    ) {
        when (dest.platform) {
            CastPlatform.AIRPLAY -> ctx.raop.sendMetadata(dest.host, metadata, ctx.artworkBytes)

            CastPlatform.AIRPLAY2 -> {
                publishToAirPlay2(dest, metadata)
                // When the receiver got AirPlay 1 instead (see AirPlay2Session).
                ctx.raop.sendMetadata(dest.host, metadata, ctx.artworkBytes)
            }

            CastPlatform.DLNA -> if (lastSentToDlna[dest.host] != key) {
                dlna.updateMetadata(dest, metadata)
                lastSentToDlna[dest.host] = key
            }

            CastPlatform.GOOGLE_CAST -> googleCast.updateMetadata(dest, metadata)

            CastPlatform.SNAPCAST, CastPlatform.ARIACAST, CastPlatform.MANUAL ->
                publishToHttpEndpoint(dest, metadata)
        }
    }

    private fun publishToAirPlay2(dest: CastDestination, metadata: TrackMetadata) {
        val client = ctx.airPlay2[dest.host] ?: return
        client.sendMetadata(
            metadata.title,
            metadata.artist,
            metadata.album,
            metadata.durationMs,
            ctx.artworkBytes
        )
        val duration = metadata.durationMs
        val position = metadata.positionMs
        if (duration != null && position != null) client.sendProgress(position, duration)
        PacketLogger.log(
            PacketDirection.OUT,
            PacketType.METADATA,
            "Metadata sent to ${dest.name} (AirPlay 2)"
        )
    }

    /** AriaCast receivers (and Snapcast's control port) take `{"data": metadata}` as JSON. */
    private suspend fun publishToHttpEndpoint(dest: CastDestination, metadata: TrackMetadata) {
        ctx.client.post {
            url {
                protocol = URLProtocol.HTTP
                host = dest.host
                port = dest.port
                path("metadata")
            }
            contentType(ContentType.Application.Json)
            setBody(mapOf("data" to metadata))
            timeout { requestTimeoutMillis = METADATA_TIMEOUT_MS }
        }
        PacketLogger.log(PacketDirection.OUT, PacketType.METADATA, "Metadata sent to ${dest.name}")
    }
}
