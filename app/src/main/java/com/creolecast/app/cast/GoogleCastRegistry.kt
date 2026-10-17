package com.creolecast.app.cast

import com.creolecast.app.googlecast.CastClient
import java.util.Collections

/**
 * The connected Google Cast clients, keyed by receiver host, through which volume,
 * metadata and teardown reach a session that [GoogleCastController] is streaming.
 */
internal class GoogleCastRegistry {
    // Mutated from independent Dispatchers.IO coroutines (session, metadata refresh,
    // volume commands), hence synchronized.
    private val clients = Collections.synchronizedMap(mutableMapOf<String, CastClient>())

    operator fun get(host: String): CastClient? = clients[host]

    fun register(host: String, client: CastClient) {
        clients[host] = client
    }

    fun unregister(host: String, client: CastClient) {
        clients.remove(host, client)
    }

    /** Sets every device to [level] (0.0 silent to 1.0 max), unmuting it when audible. */
    suspend fun setVolume(level: Double) {
        snapshot().forEach { client ->
            bestEffort("Google Cast volume") {
                if (level > 0.0 && client.muted) client.setMuted(false)
                client.setVolume(level)
            }
        }
    }

    fun snapshot(): List<CastClient> = synchronized(clients) { clients.values.toList() }

    fun clear() = clients.clear()
}
