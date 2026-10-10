package com.creolecast.app.cast

import com.creolecast.app.airplay2.AirPlay2Client
import java.util.Collections

/**
 * The connected AirPlay 2 clients, keyed by receiver host, through which volume, metadata
 * and teardown reach a session that [AirPlay2Session] is streaming.
 */
internal class AirPlay2Registry {
    // Mutated from independent Dispatchers.IO coroutines (session, metadata refresh,
    // volume commands), hence synchronized.
    private val clients = Collections.synchronizedMap(mutableMapOf<String, AirPlay2Client>())

    operator fun get(host: String): AirPlay2Client? = clients[host]

    fun register(host: String, client: AirPlay2Client) {
        clients[host] = client
    }

    fun unregister(host: String) {
        clients.remove(host)
    }

    /** Sends an absolute volume in dB (-30.0 silent to 0.0 max) to every receiver. */
    suspend fun setVolume(dB: Double) {
        snapshot().forEach { client ->
            bestEffort("AirPlay 2 volume") { client.setVolume(dB) }
        }
    }

    fun snapshot(): List<AirPlay2Client> = synchronized(clients) { clients.values.toList() }

    fun clear() = clients.clear()
}
