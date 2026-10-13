package com.creolecast.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A lost mDNS service must map back to the key its resolved entry was stored under, or
 * DiscoveryManager.onServiceLost leaves the vanished receiver in the list.
 */
class MdnsServersTest {

    private val instance = "Chromecast-Ultra-0123456789abcdef0123456789abcdef"

    private fun storedKeyOf(type: String): String {
        val server = MdnsServers.fromResolvedService(
            type,
            instance,
            "192.168.1.20",
            8009,
            mapOf("fn" to "Living Room TV".toByteArray())
        )!!
        return MdnsServers.storageKey(server.name, server.platform)
    }

    @Test
    fun `lost Google Cast receiver maps to the key its friendly name was stored under`() {
        val type = "_googlecast._tcp."
        val keys = ResolvedServiceKeys()
        keys.record(type, instance, storedKeyOf(type))

        assertEquals("Living Room TV", keys.forget(type, instance))
    }

    @Test
    fun `resolved and lost spellings of the service type match`() {
        val keys = ResolvedServiceKeys()
        keys.record("._googlecast._tcp", instance, storedKeyOf("._googlecast._tcp"))

        assertEquals("Living Room TV", keys.forget("_googlecast._tcp.", instance))
    }

    @Test
    fun `losing a service that was never resolved removes nothing`() {
        val keys = ResolvedServiceKeys()
        keys.record("_googlecast._tcp.", instance, "Living Room TV")

        // Same instance name under another service type is a different service.
        assertNull(keys.forget("_audiocast._tcp.", instance))
        assertNull(keys.forget("_googlecast._tcp.", "Chromecast-Other"))
    }
}
