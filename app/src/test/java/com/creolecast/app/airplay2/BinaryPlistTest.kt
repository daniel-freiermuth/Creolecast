package com.creolecast.app.airplay2

import org.junit.Assert.*
import org.junit.Test

class BinaryPlistTest {

    @Test
    fun `encode and decode round-trip simple strings`() {
        val input = mapOf("title" to "Test Track", "artist" to "Test Artist")
        val decoded = BinaryPlist.decode(BinaryPlist.encode(input))
        assertEquals("Test Track", decoded["title"])
        assertEquals("Test Artist", decoded["artist"])
    }

    @Test
    fun `encode and decode round-trip with long string`() {
        val longString = "A".repeat(20) // > 15 chars exercises extended length encoding
        val decoded = BinaryPlist.decode(BinaryPlist.encode(mapOf("key" to longString)))
        assertEquals(longString, decoded["key"])
    }

    @Test
    fun `encode and decode round-trip with Long values`() {
        val input = mapOf("port" to 7000L, "zero" to 0L, "big" to 70000L)
        val decoded = BinaryPlist.decode(BinaryPlist.encode(input))
        assertEquals(7000L, decoded["port"])
        assertEquals(0L, decoded["zero"])
        assertEquals(70000L, decoded["big"])
    }

    @Test
    fun `encode and decode round-trip with ByteArray value`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte(), 0xAB.toByte())
        val decoded = BinaryPlist.decode(BinaryPlist.encode(mapOf("raw" to data)))
        assertArrayEquals(data, decoded["raw"] as? ByteArray)
    }

    @Test
    fun `encode and decode empty ByteArray`() {
        val decoded = BinaryPlist.decode(BinaryPlist.encode(mapOf("empty" to ByteArray(0))))
        assertArrayEquals(ByteArray(0), decoded["empty"] as? ByteArray)
    }

    @Test
    fun `multiple DMAP keys round-trip`() {
        val input = linkedMapOf<String, Any?>(
            "dmap.itemname" to "Song Title",
            "daap.songartist" to "Artist Name",
            "daap.songalbum" to "Album Name"
        )
        val decoded = BinaryPlist.decode(BinaryPlist.encode(input))
        assertEquals("Song Title", decoded["dmap.itemname"])
        assertEquals("Artist Name", decoded["daap.songartist"])
        assertEquals("Album Name", decoded["daap.songalbum"])
    }

    @Test
    fun `encoded bytes start with bplist00 magic`() {
        val encoded = BinaryPlist.encode(mapOf("k" to "v"))
        assertEquals("bplist00", encoded.copyOf(8).toString(Charsets.UTF_8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode fails on non-plist data`() {
        BinaryPlist.decode("not a plist".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode fails on too short data`() {
        BinaryPlist.decode(ByteArray(10))
    }

    @Test
    fun `NTP session plist advertises a timing port`() {
        val uuid = java.util.UUID.randomUUID()
        val decoded = BinaryPlist.decode(
            BinaryPlist.makeSessionPlist(
                sessionUuid = uuid,
                deviceId = "AA:BB:CC:DD:EE:FF",
                name = "CreoleCast",
                model = "Linux",
                sourceVersion = "280.33",
                timingProtocol = "NTP",
                timingPort = 12345
            )
        )
        assertEquals("AA:BB:CC:DD:EE:FF", decoded["deviceID"])
        assertEquals("AA:BB:CC:DD:EE:FF", decoded["macAddress"])
        assertEquals("NTP", decoded["timingProtocol"])
        assertEquals(12345L, decoded["timingPort"])
        // PTP-only keys must be absent, or an NTP receiver rejects SETUP.
        assertNull(decoded["timingPeerInfo"])
        assertNull(decoded["timingPeerList"])
    }

    @Test
    fun `PTP session plist carries peer info instead of a timing port`() {
        val uuid = java.util.UUID.randomUUID()
        val decoded = BinaryPlist.decode(
            BinaryPlist.makeSessionPlist(
                sessionUuid = uuid,
                deviceId = "AA:BB:CC:DD:EE:FF",
                name = "CreoleCast",
                model = "Linux",
                sourceVersion = "980.71.1",
                timingProtocol = "PTP",
                timingPort = 12345,
                timingPeerId = "AA:BB:CC:DD:EE:FF",
                timingPeerAddress = "192.168.1.5"
            )
        )
        assertEquals("PTP", decoded["timingProtocol"])
        assertNull("PTP sessions must not advertise a timing port", decoded["timingPort"])
        val peer = decoded["timingPeerInfo"] as? Map<*, *>
        assertNotNull(peer)
        assertEquals("AA:BB:CC:DD:EE:FF", peer!!["ID"])
        assertEquals(listOf("192.168.1.5"), peer["Addresses"])
        assertEquals(1, (decoded["timingPeerList"] as? List<*>)?.size)
    }
}
