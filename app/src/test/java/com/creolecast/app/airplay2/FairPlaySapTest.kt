package com.creolecast.app.airplay2

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/**
 * Golden vectors were produced by the doubletake Go reference this file is
 * transcribed from (github.com/omarroth/doubletake @ ae06722,
 * internal/airplay fpsapSession.exchangeM3 / wrapKey), driven with the same
 * entropy stream [CountingRandom] yields: 126 local-SAP bytes, then the 16-byte
 * eiv, then the 16-byte key mask.
 */
class FairPlaySapTest {

    /** Deterministic entropy: the n-th byte drawn (1-based) is n mod 256. */
    private class CountingRandom : SecureRandom() {
        private var next = 1
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = (next++).toByte()
        }
    }

    private class Vector(val m2: String, val m3: String, val ekey: String)

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val rawKey = ByteArray(16) { it.toByte() }

    // Receiver SAP for the synthetic vectors: 00 01, then (i*7 + 3) for i in 2..127,
    // encrypted under the receiver-selected mode.
    private val syntheticEkey =
        "46504c59010201000000003c000000008f909192939495969798999a9b9c9d9e00000010" +
            "290d4052bdace935426c7e65f043e95429658f5fceeb4d3111ab13e10016791f5c79f5b3"

    private val modeVectors = listOf(
        Vector(
            "46504c5903010200000000820200ded2a121d4a563456b7e40b647831ebaf4f927aa017f8b8f3f43908c9f772e8c2bdbfd" +
                "2232cb8c1cc5c3b727926790a4ddc3dd10e7b8eeb92ae007c53ab15e6ccdf21809c289ccf258eebd3cbbe59586951383" +
                "6521c1f17950f6b0a828a411833e232d18379446284c45aaf5f1f2e35a5c23f5f00c59650fe6ee09c1c3513f32",
            "46504c590301030000000098008f1a9c39d15e8e50f2c2d8493280121fc6a7f7995546c29333bec4ddb8931da015100f" +
                "ae1a5cfaea2547f989283bf84f4af3f12a4e65a90cd777c832ad41aaa4514887bc24f95b83e178d36b0b7fc92e7da6c4" +
                "2a8ccfd09bdfc34d22034ae53aa7dd584b28e2be80c480a2c84cb48c56e9e5bb681890e9d07a8f43942ceb3a8cb7fcbe" +
                "d026e830830d0c2d57a5bdcc2eae22b436cd1a36",
            syntheticEkey,
        ),
        Vector(
            "46504c5903010200000000820201524a7f3395d7f3879d59eba96876637dd1e806f978e80c1c591c9122a36dbf693412" +
                "37b496f9e447ce422fa06119c828645f0b73219b1328459b453f75e4721493cbb1f5c872a48efc2cd1f382826bdcbc39" +
                "f2490fa27bcb11b1d4f686e0a6075bab2d5865574e469b0444452938d6619caf52012d73863d9d62446a73bd2442",
            "46504c590301030000000098018f1a9cf9cff7f8f5b1f7c308ecb45d8cc39c84bce3263a4c71ca8addfbcf29e3030b34" +
                "9413a5550eec9ec9400f9db2cb42bdf60a8b168ba6f90463dce4dfc7610fc52d1624042edfd89cd1c533999ef0946ca3" +
                "c4c82a1a9395b01ef33238091eef6d94d634dc5c11ff9dc4b3f7427f67c0a45e489f7ae9269eeeaac8cb1508f4f25016" +
                "d026e830830d0c2d57a5bdcc2eae22b436cd1a36",
            syntheticEkey,
        ),
        Vector(
            "46504c59030102000000008202021ecf8506f4d97fc054f8e84d2788ab5b4123e8dcd92970901c229f7fbe9381dd2b9b" +
                "419ed6beb07ccabec960d9763ca59c00d2a040b0c2b5f80e04c644d50c1ab5d9215b3d030a0cf24e73ff9177c2468223" +
                "5e65a6f84ac8aa41b28b8bf2431eed488f7a2da20d19f9f7ef5725565826bad0f4f158a68a4d0e9636d62deb5d15",
            "46504c590301030000000098028f1a9c82b518e999c554590666a5f9b04677a620d768abf59c8fa06b49f4997b9b52ce" +
                "a37089f2702c0143e9f01ee8a95c5a14da96365c2f631ad5a8e162eae6163c1acc97f493eb3281581cf25be0b2b2c565" +
                "7b6146c8e7fd8383aea30500f6c5a79e62a71930696cd011c7732eeaeb763caf6ede022cab2c18078e45904569243fe0" +
                "d026e830830d0c2d57a5bdcc2eae22b436cd1a36",
            syntheticEkey,
        ),
        Vector(
            "46504c59030102000000008202037be675519e570d8e7c194af31bdfd65655096473de37215002de211eec68264a05a0" +
                "7cb60ca93c5208d40268d94ad39df24b35790cc0385554a769cb7337c1b5b8e55e6cb1d79647f840c4a3b60da865f083" +
                "4ddc37614f44500c892048247702b2f5cc9178046e68968ee7f765e6cede5b6baac8606c8e3781e2fdf0253696f6",
            "46504c590301030000000098038f1a9c7ddd506f27dfcaf90b08b73940966603a44b2ddb4df5ea46b9846ae655124558" +
                "231ace6d9cc1419392a7d2e7852139b94301f227ab0427b748961c0fb9e2a27a8929618638e29a99a6d5743bb03273f5" +
                "f98932495b3abfad59110341f892728cee7447a7e3eea1e7a29825da08be9dd4cfe45b5a3e925ad6d4169a217f7fb067" +
                "d026e830830d0c2d57a5bdcc2eae22b436cd1a36",
            syntheticEkey,
        ),
    )

    // m2 captured from a real receiver (doubletake fpsap_test.go, captured-m2).
    private val capturedVector = Vector(
        "46504c59030102000000008202034a114c26b77d4e2eec2c8f89fdb653b5b32d3576bc176816d110a14c3f53c08dbb93" +
            "6183bfdfe0a4f3c12e85216003b46f738c40c54da6c436d29d1b342d63c7b314309ae79a33bb1787709ef077cbfe4190" +
            "117a3423e270fd1a2eac44da1a7934f59dc681d1b70783f228c4d077c2d495f5285c3bf8df586fc2ebfe17fb5b65",
        "46504c590301030000000098038f1a9c7ddd506f27dfcaf90b08b73940966603a44b2ddb4df5ea46b9846ae655124558" +
            "231ace6d9cc1419392a7d2e7852139b94301f227ab0427b748961c0fb9e2a27a8929618638e29a99a6d5743bb03273f5" +
            "f98932495b3abfad59110341f892728cee7447a7e3eea1e7a29825da08be9dd4cfe45b5a3e925ad6d4169a217f7fb067" +
            "dc8a5ca78fce0d6f94784a37aec97f853140519c",
        "46504c59010201000000003c000000008f909192939495969798999a9b9c9d9e00000010" +
            "c39e62eb5a8596783f9573083c06a5050be8b5970460888e43a1431bab3360016cafe8cf",
    )

    private val validM2 get() = hex(capturedVector.m2)

    private fun m4For(m3: ByteArray): ByteArray =
        hex("46504c590301040000000014") + m3.copyOfRange(144, 164)

    private fun assertGolden(vector: Vector) {
        val session = FairPlaySapSession(CountingRandom())
        val m3 = session.exchangeM3(hex(vector.m2))
        assertEquals("m3", vector.m3, toHex(m3))
        session.finish(m4For(m3))
        val keys = session.wrapKey(rawKey)
        assertEquals("eiv", toHex(ByteArray(16) { (127 + it).toByte() }), toHex(keys.eiv))
        assertEquals("ekey", vector.ekey, toHex(keys.ekey))
    }

    // --- message framing ---

    @Test
    fun `message1 is the fixed FPLY v3 type-1 record`() {
        assertEquals(
            "46504c590301010000000004020003bb",
            toHex(FairPlaySapSession(CountingRandom()).message1()),
        )
    }

    // --- golden vectors against the Go reference ---

    @Test
    fun `captured receiver m2 yields reference m3 and ekey`() {
        assertGolden(capturedVector)
    }

    @Test
    fun `every receiver-selected mode yields reference m3 and ekey`() {
        for ((mode, vector) in modeVectors.withIndex()) {
            try {
                assertGolden(vector)
            } catch (e: AssertionError) {
                throw AssertionError("mode $mode: ${e.message}", e)
            }
        }
    }

    // --- m2 validation: each case is a valid m2 with exactly one field broken ---

    private fun assertM2Rejected(m2: ByteArray) {
        val session = FairPlaySapSession(CountingRandom())
        assertThrows(IllegalArgumentException::class.java) { session.exchangeM3(m2) }
        // A rejected m2 must not leave the session able to wrap keys.
        assertThrows(IllegalStateException::class.java) { session.wrapKey(rawKey) }
    }

    @Test
    fun `m2 with wrong total length is rejected`() {
        assertM2Rejected(validM2.copyOf(141))
        assertM2Rejected(validM2.copyOf(143))
        assertM2Rejected(ByteArray(0))
    }

    @Test
    fun `m2 with bad magic is rejected`() {
        assertM2Rejected(validM2.also { it[3] = 'Z'.code.toByte() })
    }

    @Test
    fun `m2 with wrong version or message type is rejected`() {
        for ((index, value) in listOf(4 to 2, 5 to 2, 6 to 3, 6 to 0x82, 7 to 1)) {
            assertM2Rejected(validM2.also { it[index] = value.toByte() })
        }
    }

    @Test
    fun `m2 whose declared payload length disagrees is rejected`() {
        assertM2Rejected(validM2.also { it[11] = 0x81.toByte() })
        assertM2Rejected(validM2.also { it[8] = 1 })
    }

    @Test
    fun `m2 with payload marker other than 2 is rejected`() {
        for (marker in listOf(0, 1, 3, 0x82)) {
            assertM2Rejected(validM2.also { it[12] = marker.toByte() })
        }
    }

    @Test
    fun `m2 selecting an unsupported mode is rejected`() {
        for (mode in listOf(4, 0x7f, 0x80, 0xff)) {
            assertM2Rejected(validM2.also { it[13] = mode.toByte() })
        }
    }

    // --- lifecycle and m4 confirmation ---

    @Test
    fun `finish before exchangeM3 throws IllegalStateException`() {
        val session = FairPlaySapSession(CountingRandom())
        assertThrows(IllegalStateException::class.java) { session.finish(m4For(ByteArray(164))) }
    }

    @Test
    fun `wrapKey before exchangeM3 throws IllegalStateException`() {
        val session = FairPlaySapSession(CountingRandom())
        assertThrows(IllegalStateException::class.java) { session.wrapKey(rawKey) }
    }

    @Test
    fun `m4 whose confirmation differs from the m3 tail is rejected`() {
        val session = FairPlaySapSession(CountingRandom())
        val m3 = session.exchangeM3(validM2)
        for (index in listOf(12, 31)) {
            val m4 = m4For(m3).also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertThrows(IllegalStateException::class.java) { session.finish(m4) }
        }
    }

    @Test
    fun `malformed m4 record is rejected`() {
        val session = FairPlaySapSession(CountingRandom())
        val m4 = m4For(session.exchangeM3(validM2))
        assertThrows(IllegalArgumentException::class.java) { session.finish(m4.copyOf(31)) }
        assertThrows(IllegalArgumentException::class.java) { session.finish(m4.copyOf().also { it[6] = 3 }) }
        assertThrows(IllegalArgumentException::class.java) { session.finish(m4.copyOf().also { it[11] = 21 }) }
    }

    @Test
    fun `mutating the returned m3 does not alter the session`() {
        val session = FairPlaySapSession(CountingRandom())
        val m3 = session.exchangeM3(hex(capturedVector.m2))
        m3.fill(0)
        session.finish(m4For(hex(capturedVector.m3)))
        assertEquals(capturedVector.ekey, toHex(session.wrapKey(rawKey).ekey))
    }

    @Test
    fun `wrapKey rejects stream keys that are not 16 bytes`() {
        val session = FairPlaySapSession(CountingRandom())
        session.exchangeM3(validM2)
        for (size in listOf(0, 15, 17, 32)) {
            assertThrows(IllegalArgumentException::class.java) { session.wrapKey(ByteArray(size)) }
        }
    }
}
