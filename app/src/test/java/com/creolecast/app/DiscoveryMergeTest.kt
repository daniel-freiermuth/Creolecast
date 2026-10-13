package com.creolecast.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for how [DiscoveryManager.handleResolvedService] keys and merges resolved mDNS
 * services ([MdnsServers.place]) and how a lost service finds its entry again
 * ([MdnsServers.lostServiceKey]). The keying is what keeps a same-name device on another
 * host - possibly a spoofer - from taking over or merging its TXT fields (notably `pk`,
 * which AirPlay 2 pair-verify pins) into an already-listed receiver.
 */
class DiscoveryMergeTest {

    private val servers = LinkedHashMap<String, Server>()

    private fun resolved(
        type: String,
        name: String,
        host: String,
        vararg txt: Pair<String, String>
    ): Server = checkNotNull(
        MdnsServers.fromResolvedService(
            type,
            name,
            host,
            7000,
            txt.associate { (k, v) -> k to v.toByteArray(Charsets.UTF_8) }
        )
    ) { "unsupported service type $type" }

    private fun airplay2(host: String, vararg txt: Pair<String, String>) =
        resolved(AIRPLAY2, SPEAKER, host, *txt)

    private fun add(server: Server, isRaop: Boolean = false) {
        val (key, entry) = MdnsServers.place(servers, server, isRaop)
        servers[key] = entry
    }

    private fun pk(server: Server?) = ExtraFields.get(server?.extra, "pk")

    @Test
    fun `same name on another host is listed separately under a disambiguated name`() {
        add(resolved(AUDIOCAST, "Kitchen", HOST))
        add(resolved(AUDIOCAST, "Kitchen", OTHER_HOST))

        assertEquals(HOST, servers.getValue("Kitchen").host)
        assertEquals("Kitchen", servers.getValue("Kitchen").name)
        val spoof = servers.getValue("Kitchen@$OTHER_HOST")
        assertEquals("Kitchen ($OTHER_HOST)", spoof.name)
        assertEquals(OTHER_HOST, spoof.host)
        assertEquals(2, servers.size)
    }

    @Test
    fun `AirPlay 2 from another host does not merge its pk into the listed receiver`() {
        add(airplay2(HOST, "pk" to "legit", "features" to "0x1"))
        add(airplay2(OTHER_HOST, "pk" to "evil", "features" to "0x2"))

        val real = servers.getValue("$SPEAKER::AirPlay2")
        assertEquals(HOST, real.host)
        assertEquals("legit", pk(real))
        assertEquals("0x1", ExtraFields.get(real.extra, "features"))

        val spoof = servers.getValue("$SPEAKER@$OTHER_HOST::AirPlay2")
        assertEquals("$SPEAKER ($OTHER_HOST)", spoof.name)
        assertEquals("evil", pk(spoof))
        assertEquals(2, servers.size)
    }

    @Test
    fun `a name another platform already lists elsewhere still disambiguates AirPlay 2`() {
        add(resolved(AUDIOCAST, SPEAKER, OTHER_HOST))
        add(airplay2(HOST, "pk" to "legit"))

        assertEquals("$SPEAKER ($HOST)", servers.getValue("$SPEAKER@$HOST::AirPlay2").name)
        assertEquals(2, servers.size)
    }

    @Test
    fun `listed AirPlay 2 receiver re-announcing merges though another platform reuses its name`() {
        add(airplay2(HOST, "pk" to "legit"))
        add(resolved(AUDIOCAST, SPEAKER, OTHER_HOST))
        add(airplay2(HOST, "pk" to "rotated"))

        val entry = servers.getValue("$SPEAKER::AirPlay2")
        assertEquals(SPEAKER, entry.name)
        assertEquals("rotated", pk(entry))
        assertEquals(2, servers.size)
    }

    @Test
    fun `re-announcement from the same host updates TXT fields and keeps a real version`() {
        add(airplay2(HOST, "pk" to "old", "srcvers" to "366.0"))
        add(airplay2(HOST, "pk" to "new", "model" to "AudioAccessory5,1"))

        val entry = servers.getValue("$SPEAKER::AirPlay2")
        assertEquals(1, servers.size)
        assertEquals(SPEAKER, entry.name)
        assertEquals("new", pk(entry))
        assertEquals("AudioAccessory5,1", ExtraFields.get(entry.extra, "model"))
        // The second TXT carried no version; the default must not overwrite the real one.
        assertEquals("366.0", entry.version)

        add(airplay2(HOST, "srcvers" to "377.1"))
        assertEquals("377.1", servers.getValue("$SPEAKER::AirPlay2").version)
    }

    @Test
    fun `AirPlay and AirPlay 2 for one device are separate entries and never merged`() {
        add(resolved(RAOP, RAOP_NAME, HOST, "pk" to "raop"), isRaop = true)
        add(airplay2(HOST, "pk" to "ap2"))

        assertEquals("raop", pk(servers.getValue("$SPEAKER::AirPlay")))
        assertEquals("ap2", pk(servers.getValue("$SPEAKER::AirPlay2")))
        assertEquals(2, servers.size)
    }

    @Test
    fun `lost services resolve to the key their entry was stored under`() {
        add(airplay2(HOST))
        add(resolved(AUDIOCAST, "Kitchen", OTHER_HOST))
        add(resolved(RAOP, RAOP_NAME, HOST), isRaop = true)

        val lost = listOf(AIRPLAY2 to SPEAKER, AUDIOCAST to "Kitchen", RAOP to RAOP_NAME)
        for ((type, name) in lost) {
            val key = MdnsServers.lostServiceKey(type, name)
            assertTrue("$type $name -> $key", servers.containsKey(key))
            servers.remove(key)
        }
        assertTrue(servers.isEmpty())
    }

    @Test
    fun `blank advertised names fall back to the service instance name`() {
        assertEquals(
            "Chromecast-1234",
            resolved("_googlecast._tcp.", "Chromecast-1234", HOST, "fn" to "   ").name
        )
        assertEquals("AABBCCDDEEFF@", resolved(RAOP, "AABBCCDDEEFF@", HOST).name)
    }

    @Test
    fun `loopback resolves are ignored`() {
        assertTrue(DiscoveryManager.isLoopbackHost("127.0.0.1"))
        assertTrue(DiscoveryManager.isLoopbackHost("::1"))
        assertFalse(DiscoveryManager.isLoopbackHost(HOST))
    }

    private companion object {
        const val AIRPLAY2 = "_airplay._tcp."
        const val AUDIOCAST = "_audiocast._tcp."
        const val RAOP = "_raop._tcp."
        const val SPEAKER = "Speaker"
        const val RAOP_NAME = "AABBCCDDEEFF@$SPEAKER"
        const val HOST = "192.168.1.10"
        const val OTHER_HOST = "192.168.1.66"
    }
}
