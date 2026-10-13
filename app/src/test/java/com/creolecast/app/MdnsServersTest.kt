package com.creolecast.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A lost mDNS service must map back to the key its resolved entry was stored under, or
 * DiscoveryManager.onServiceLost leaves the vanished receiver in the list.
 */
class MdnsServersTest {

    @Test
    fun `lost Google Cast receiver maps to the key its friendly name was stored under`() {
        val type = "_googlecast._tcp."
        val instance = "Chromecast-Ultra-0123456789abcdef0123456789abcdef"
        val server = MdnsServers.fromResolvedService(
            type,
            instance,
            "192.168.1.20",
            8009,
            mapOf("fn" to "Living Room TV".toByteArray())
        )!!
        val stored = MdnsServers.storageKey(server.name, server.platform)

        assertEquals(stored, MdnsServers.lostServiceKey(type, instance))
    }
}
