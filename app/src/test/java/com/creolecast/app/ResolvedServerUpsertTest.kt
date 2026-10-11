package com.creolecast.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [MdnsServers.resolvedEntry]: a second device resolving under an
 * already-discovered friendly name must be listed next to the first, not replace it.
 */
class ResolvedServerUpsertTest {

    private fun server(name: String, host: String, platform: CastPlatform, port: Int = 7000) =
        Server(
            name = name,
            host = host,
            port = port,
            version = "1.0",
            codecs = listOf("pcm"),
            sampleRate = 48000,
            channels = 2,
            platform = platform,
            extra = null
        )

    private fun upsertAll(vararg resolved: Pair<Server, Boolean>): Map<String, Server> {
        val servers = mutableMapOf<String, Server>()
        resolved.forEach { (server, isRaop) ->
            val (key, entry) = MdnsServers.resolvedEntry(servers, server, isRaop)
            servers[key] = entry
        }
        return servers
    }

    @Test
    fun `AirPlay device reusing a discovered name does not replace it`() {
        val servers = upsertAll(
            server("LivingRoom", "192.168.1.10", CastPlatform.AIRPLAY) to true,
            server("LivingRoom", "192.168.1.66", CastPlatform.AIRPLAY) to true
        )

        assertEquals(
            setOf("192.168.1.10" to "LivingRoom", "192.168.1.66" to "LivingRoom (192.168.1.66)"),
            servers.values.map { it.host to it.name }.toSet()
        )
    }

    @Test
    fun `AirPlay 2 device reusing a discovered name does not replace it`() {
        val servers = upsertAll(
            server("LivingRoom", "192.168.1.10", CastPlatform.AIRPLAY2) to false,
            server("LivingRoom", "192.168.1.66", CastPlatform.AIRPLAY2) to false
        )

        assertEquals(
            setOf("192.168.1.10" to "LivingRoom", "192.168.1.66" to "LivingRoom (192.168.1.66)"),
            servers.values.map { it.host to it.name }.toSet()
        )
    }

    @Test
    fun `re-resolving the same AirPlay device keeps a single entry`() {
        val servers = upsertAll(
            server("LivingRoom", "192.168.1.10", CastPlatform.AIRPLAY) to true,
            server("LivingRoom", "192.168.1.10", CastPlatform.AIRPLAY, port = 7001) to true
        )

        assertEquals(listOf("LivingRoom" to 7001), servers.values.map { it.name to it.port })
    }
}
