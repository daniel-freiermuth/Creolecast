package com.creolecast.app.airplay2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `non-ASCII strings round-trip`() {
        val input = linkedMapOf<String, Any?>(
            "dmap.itemname" to "Café del Mar",
            "daap.songartist" to "ビートルズ",
            "daap.songalbum" to "Ünïcödé Ålbum Title Longer Than Fifteen"
        )
        val decoded = BinaryPlist.decode(BinaryPlist.encode(input))
        assertEquals("Café del Mar", decoded["dmap.itemname"])
        assertEquals("ビートルズ", decoded["daap.songartist"])
        assertEquals("Ünïcödé Ålbum Title Longer Than Fifteen", decoded["daap.songalbum"])
    }

    @Test
    fun `non-ASCII string uses the UTF-16BE marker`() {
        // {"k": "é"}: objects are [dict, "k", "é"], so "é" is the last object.
        val encoded = BinaryPlist.encode(mapOf("k" to "é"))
        val trailer = encoded.copyOfRange(encoded.size - 32, encoded.size)
        val offsetTableStart = java.nio.ByteBuffer.wrap(trailer, 24, 8).long.toInt()
        val stringOffset = encoded[offsetTableStart + 2].toInt() and 0xFF
        assertEquals(0x61, encoded[stringOffset].toInt() and 0xFF) // 0x6 type, 1 UTF-16 unit
        assertEquals(0x00, encoded[stringOffset + 1].toInt() and 0xFF)
        assertEquals(0xE9, encoded[stringOffset + 2].toInt() and 0xFF)
    }

    @Test
    fun `dict with more than 256 objects round-trips`() {
        // 1 dict + 200 keys + 200 values = 401 objects, forcing 2-byte object refs.
        val input = linkedMapOf<String, Any?>()
        for (i in 0 until 200) input["key-$i"] = "value-$i"
        val decoded = BinaryPlist.decode(BinaryPlist.encode(input))
        assertEquals(input, decoded)
    }

    @Test
    fun `collection and string lengths 14 and 15 round-trip`() {
        for (n in listOf(14, 15)) {
            val dict = linkedMapOf<String, Any?>()
            for (i in 0 until n) dict["k$i"] = "v$i"
            val list = (0 until n).map { "item-$it" }
            val input = linkedMapOf<String, Any?>(
                "dict" to dict,
                "list" to list,
                "str" to "s".repeat(n),
                "data" to ByteArray(n) { it.toByte() }
            )
            val decoded = BinaryPlist.decode(BinaryPlist.encode(input))
            assertEquals(dict, decoded["dict"])
            assertEquals(list, decoded["list"])
            assertEquals("s".repeat(n), decoded["str"])
            assertArrayEquals(ByteArray(n) { it.toByte() }, decoded["data"] as? ByteArray)
        }
    }

    @Test
    fun `offset table widens past 255 and 65535 bytes`() {
        // Total object bytes of ~300 and ~70 000 force 2- and 4-byte offset entries.
        // Each blob stays under 64 KiB so only the offset-table width varies.
        for (blobCount in listOf(1, 10)) {
            val blobSize = if (blobCount == 1) 300 else 7_000
            val blobs = List(blobCount) { b -> ByteArray(blobSize) { ((it + b) % 251).toByte() } }
            val input = linkedMapOf<String, Any?>()
            blobs.forEachIndexed { b, blob -> input["raw$b"] = blob }
            input["tail"] = "end"
            val decoded = BinaryPlist.decode(BinaryPlist.encode(input))
            blobs.forEachIndexed { b, blob ->
                assertArrayEquals(blob, decoded["raw$b"] as? ByteArray)
            }
            assertEquals("end", decoded["tail"])
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode fails on non-plist data`() {
        BinaryPlist.decode("not a plist".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode fails on too short data`() {
        BinaryPlist.decode(ByteArray(10))
    }

    // Golden vectors below come from Python's plistlib (FMT_BINARY), which
    // writes the same bplist00 layout as CoreFoundation, or are hand-built and
    // validated with plistlib.loads. They cover object kinds and trailer
    // widths that BinaryPlist.encode never emits but receivers do.

    @Test
    fun `decodes UTF-16 string from a foreign plist`() {
        // {"name": "Salon é"} -- non-ASCII forces a 0x6x UTF-16BE string object
        val decoded = BinaryPlist.decode(
            hex(
                "62706c6973743030d10102546e616d656700530061006c006f006e002000e9" +
                    "080b10000000000000010100000000000000030000000000000000000000000000001f"
            )
        )
        assertEquals("Salon é", decoded["name"])
    }

    @Test
    fun `decodes plist with 2-byte object refs and offsets`() {
        // {"type": "volume", "eventPort": 7000}, offsetIntSize=2, objectRefSize=2
        val decoded = BinaryPlist.decode(
            hex(
                "62706c6973743030d200010002000300045474797065596576656e74506f7274" +
                    "56766f6c756d65111b5800080011001600200027000000000000020200000000" +
                    "000000050000000000000000000000000000002a"
            )
        )
        assertEquals(mapOf("type" to "volume", "eventPort" to 7000L), decoded)
    }

    @Test
    fun `decodes nested SETUP response dict`() {
        // {"eventPort": 50123, "timingPeerInfo": {"ClockID": 0x1122334455667788},
        //  "streams": [{"type": 96, "dataPort": 6001, "controlPort": 6002}]}
        val decoded = BinaryPlist.decode(
            hex(
                "62706c6973743030d3010203040508596576656e74506f72745e74696d696e67" +
                    "50656572496e666f5773747265616d7311c3cbd1060757436c6f636b49441311" +
                    "22334455667788a109d30a0b0c0d0e0f54747970655864617461506f72745b63" +
                    "6f6e74726f6c506f72741060111771111772080f19283033363e474950555e6a" +
                    "6c6f000000000000010100000000000000100000000000000000000000000000" +
                    "0072"
            )
        )
        assertEquals(50123L, decoded["eventPort"])
        val timingPeerInfo = decoded["timingPeerInfo"] as? Map<*, *>
        assertEquals(0x1122334455667788L, timingPeerInfo?.get("ClockID"))
        val stream = (decoded["streams"] as? List<*>)?.single() as? Map<*, *>
        assertEquals(96L, stream?.get("type"))
        assertEquals(6001L, stream?.get("dataPort"))
        assertEquals(6002L, stream?.get("controlPort"))
    }

    @Test
    fun `decode returns empty map when root is not a dict`() {
        // [1, 2]
        val decoded = BinaryPlist.decode(
            hex(
                "62706c6973743030a2010210011002080b0d0000000000000101000000000000" +
                    "00030000000000000000000000000000000f"
            )
        )
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `decodes 8-byte real from a receiver volume event`() {
        // {"type": "volume", "value": -20.0} -- 0x23 marker, bits 0xC034000000000000
        val decoded = BinaryPlist.decode(
            hex(
                "62706c6973743030d20102030454747970655576616c756556766f6c756d6523" +
                    "c034000000000000080d12181f00000000000001010000000000000005000000" +
                    "00000000000000000000000028"
            )
        )
        assertEquals("volume", decoded["type"])
        assertEquals(-20.0, decoded["value"])
    }

    @Test
    fun `decodes 4-byte real`() {
        // {"value": -7.5f} -- 0x22 marker, bits 0xC0F00000
        val decoded = BinaryPlist.decode(
            hex(
                "62706c6973743030d101025576616c756522c0f00000080b1100000000000001" +
                    "01000000000000000300000000000000000000000000000016"
            )
        )
        assertEquals(-7.5, decoded["value"])
    }

    @Test
    fun `NTP session plist advertises a timing port`() {
        val uuid = java.util.UUID.randomUUID()
        val decoded = BinaryPlist.decode(
            BinaryPlist.makeSessionPlist(
                SessionPlistParams(
                    sessionUuid = uuid,
                    deviceId = "AA:BB:CC:DD:EE:FF",
                    name = "CreoleCast",
                    model = "Linux",
                    sourceVersion = "280.33",
                    timingProtocol = "NTP",
                    timingPort = 12345
                )
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
                SessionPlistParams(
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
        )
        assertEquals("PTP", decoded["timingProtocol"])
        assertNull("PTP sessions must not advertise a timing port", decoded["timingPort"])
        val peer = decoded["timingPeerInfo"] as? Map<*, *>
        assertNotNull(peer)
        assertEquals("AA:BB:CC:DD:EE:FF", peer!!["ID"])
        assertEquals(listOf("192.168.1.5"), peer["Addresses"])
        assertEquals(1, (decoded["timingPeerList"] as? List<*>)?.size)
    }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
