package com.creolecast.app.airplay2

import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.srp.SRP6Server
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator
import org.bouncycastle.crypto.digests.SHA512Digest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SRP6aClientTest {

    @Test
    fun `buildM1 contains STATE=1 and METHOD=0`() {
        val srp = SRP6aClient("1234")
        val parsed = TlvUtil.parse(srp.buildM1(transient = false))
        assertEquals(1.toByte(), parsed[TlvUtil.TLV_STATE]?.firstOrNull()?.get(0))
        assertEquals(0.toByte(), parsed[TlvUtil.TLV_METHOD]?.firstOrNull()?.get(0))
    }

    @Test
    fun `full pairing M1 carries no flags TLV`() {
        val parsed = TlvUtil.parse(SRP6aClient("1234").buildM1(transient = false))
        assertTrue(parsed.keys.all { it == TlvUtil.TLV_STATE || it == TlvUtil.TLV_METHOD })
    }

    @Test
    fun `transient M1 carries the transient flag`() {
        // The flag must be the single byte 0x10: receivers such as shairport-sync
        // reject any other length and fall back to full PIN pairing.
        val parsed = TlvUtil.parse(SRP6aClient("3939").buildM1(transient = true))
        assertArrayEquals(byteArrayOf(0x10), parsed[TlvUtil.TLV_FLAGS]?.firstOrNull())
    }

    @Test
    fun `sharedKeyBytes is null before pairing`() {
        assertNull(SRP6aClient("3939").sharedKeyBytes)
    }

    @Test
    fun `processM2 returns null when state byte is wrong`() {
        val srp = SRP6aClient("3939")
        srp.buildM1(transient = false)
        val fakeM2 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(5), // wrong state (should be 2)
            TlvUtil.TLV_SALT to ByteArray(16),
            TlvUtil.TLV_PUBLIC_KEY to ByteArray(384) { 0x42 }
        )
        assertNull(srp.processM2(fakeM2))
    }

    @Test
    fun `processM2 returns null when salt is wrong size`() {
        val srp = SRP6aClient("3939")
        srp.buildM1(transient = false)
        val fakeM2 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(2),
            TlvUtil.TLV_SALT to ByteArray(8), // should be 16 bytes
            TlvUtil.TLV_PUBLIC_KEY to ByteArray(384) { 0x42 }
        )
        assertNull(srp.processM2(fakeM2))
    }

    @Test
    fun `processM2 rejects a degenerate B that is 0 mod N (RFC 5054 3-1)`() {
        val srp = SRP6aClient("3939")
        srp.buildM1(transient = false)
        val fakeM2 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(2),
            TlvUtil.TLV_SALT to ByteArray(16) { it.toByte() },
            TlvUtil.TLV_PUBLIC_KEY to ByteArray(384) // all-zero => B == 0 => B mod N == 0
        )
        assertNull(srp.processM2(fakeM2))
    }

    @Test
    fun `processM2 rejects B equal to N (still 0 mod N)`() {
        val srp = SRP6aClient("3939")
        srp.buildM1(transient = false)
        val nBytes = SRP6aClient.N.toByteArray().let {
            if (it[0] ==
                0.toByte()
            ) {
                it.copyOfRange(1, it.size)
            } else {
                it
            }
        }
        val fakeM2 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(2),
            TlvUtil.TLV_SALT to ByteArray(16) { it.toByte() },
            TlvUtil.TLV_PUBLIC_KEY to nBytes
        )
        assertNull(srp.processM2(fakeM2))
    }

    @Test
    fun `processM2 returns null on empty body`() {
        val srp = SRP6aClient("3939")
        srp.buildM1(transient = false)
        assertNull(srp.processM2(ByteArray(0)))
    }

    @Test
    fun `verifyM4 returns false on wrong proof`() {
        val srp = SRP6aClient("3939")
        srp.buildM1(transient = false)
        // Synthesize a fake M2 with correct state and sizes so processM2 proceeds
        val fakeM2 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(2),
            TlvUtil.TLV_SALT to ByteArray(16) { it.toByte() },
            TlvUtil.TLV_PUBLIC_KEY to ByteArray(384) { 0x42 }
        )
        val m3 = srp.processM2(fakeM2) // runs SRP math on fake B
        assertNotNull("processM2 should succeed with valid sizes", m3)

        // Now send a wrong M4 (random proof)
        val fakeM4 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(4),
            TlvUtil.TLV_PROOF to ByteArray(64) { 0xFF.toByte() } // wrong proof
        )
        assertFalse(srp.verifyM4(fakeM4))
    }

    @Test
    fun `buildM1 result is parseable as TLV`() {
        val m1 = SRP6aClient("0000").buildM1(transient = false)
        assertFalse(TlvUtil.parse(m1).isEmpty())
    }

    @Test
    fun `different pins produce same M1 structure`() {
        // M1 is always the same structure regardless of PIN (PIN is used in M3)
        val m1a = TlvUtil.parse(SRP6aClient("1234").buildM1(transient = false))
        val m1b = TlvUtil.parse(SRP6aClient("5678").buildM1(transient = false))
        assertArrayEquals(
            m1a[TlvUtil.TLV_STATE]?.firstOrNull(),
            m1b[TlvUtil.TLV_STATE]?.firstOrNull()
        )
        assertArrayEquals(
            m1a[TlvUtil.TLV_METHOD]?.firstOrNull(),
            m1b[TlvUtil.TLV_METHOD]?.firstOrNull()
        )
    }

    @Test
    fun `client agrees with a reference SRP-6a server on K and M1 and accepts its M4 proof`() {
        val server = ReferenceSrpServer("3939", seededRandom(1))
        val srp = SRP6aClient("3939", random = seededRandom(2))

        val m3 = TlvUtil.parse(checkNotNull(srp.processM2(server.m2())))
        assertArrayEquals(byteArrayOf(3), m3[TlvUtil.TLV_STATE]?.firstOrNull())
        val wireA = m3.getValue(TlvUtil.TLV_PUBLIC_KEY).single()
        assertEquals("A travels padded to the 3072-bit group size", 384, wireA.size)

        val session = server.accept(wireA)
        assertArrayEquals(session.sessionKeyK, srp.sharedKeyBytes)
        assertArrayEquals(session.clientProofM1, m3[TlvUtil.TLV_PROOF]?.firstOrNull())
        assertTrue(srp.verifyM4(session.m4()))
    }

    @Test
    fun `wrong PIN yields a different K and M1 and rejects the server proof`() {
        val server = ReferenceSrpServer("3939", seededRandom(1))
        val srp = SRP6aClient("1234", random = seededRandom(2))

        val m3 = TlvUtil.parse(checkNotNull(srp.processM2(server.m2())))
        val session = server.accept(m3.getValue(TlvUtil.TLV_PUBLIC_KEY).single())

        assertFalse(session.sessionKeyK.contentEquals(srp.sharedKeyBytes))
        assertFalse(session.clientProofM1.contentEquals(m3.getValue(TlvUtil.TLV_PROOF).single()))
        // A receiver that does not hold the PIN's verifier cannot forge an accepted M4.
        assertFalse(srp.verifyM4(session.m4()))
    }

    @Test
    fun `verifyM4 accepts an M4 that omits the proof, matching pairing go`() {
        // Security-relevant policy: the receiver is not authenticated when it omits
        // TLV_PROOF. Changing this must be a deliberate decision, not a side effect.
        val srp = SRP6aClient("3939", random = seededRandom(2))
        checkNotNull(srp.processM2(ReferenceSrpServer("3939", seededRandom(1)).m2()))
        assertTrue(srp.verifyM4(TlvUtil.build(TlvUtil.TLV_STATE to byteArrayOf(4))))
    }

    @Test
    fun `verifyM4 fails on an error TLV even when the proof is correct`() {
        val server = ReferenceSrpServer("3939", seededRandom(1))
        val srp = SRP6aClient("3939", random = seededRandom(2))
        val m3 = TlvUtil.parse(checkNotNull(srp.processM2(server.m2())))
        val session = server.accept(m3.getValue(TlvUtil.TLV_PUBLIC_KEY).single())

        val m4 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(4),
            TlvUtil.TLV_PROOF to session.serverProof(),
            TlvUtil.TLV_ERROR to byteArrayOf(2)
        )
        assertFalse(srp.verifyM4(m4))
    }

    @Test
    fun `verifyM4 before processM2 rejects a proof instead of throwing`() {
        val m4 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(4),
            TlvUtil.TLV_PROOF to ByteArray(64)
        )
        assertFalse(SRP6aClient("3939").verifyM4(m4))
    }

    @Test
    fun `buildM5 returns null before processM2`() {
        assertNull(SRP6aClient("3939").buildM5(AirPlay2Credentials.generate(), false))
    }

    @Test
    fun `buildM5 encrypts a signed long-term identity the receiver can open and verify`() {
        val (session, srp) = completedExchange()
        val credentials = AirPlay2Credentials.generate()

        val sub = session.openM5(
            checkNotNull(srp.buildM5(credentials, includeScreenCaptureAcl = false))
        )

        val identifier = sub.getValue(TlvUtil.TLV_IDENTIFIER).single()
        val publicKey = sub.getValue(TlvUtil.TLV_PUBLIC_KEY).single()
        assertArrayEquals(credentials.pairingId.toByteArray(Charsets.UTF_8), identifier)
        assertArrayEquals(credentials.ed25519Public, publicKey)
        val signedData = session.controllerSignKey() + identifier + publicKey
        assertTrue(
            AirPlay2Crypto.ed25519Verify(
                credentials.ed25519Public,
                signedData,
                sub.getValue(TlvUtil.TLV_SIGNATURE).single()
            )
        )
        assertNull("ACL must be omitted for non-screen-capture pairing", sub[TlvUtil.TLV_ACL])
    }

    @Test
    fun `buildM5 with screen capture adds exactly the ScreenCapture OPACK ACL`() {
        val (session, srp) = completedExchange()
        val sub = session.openM5(
            checkNotNull(
                srp.buildM5(AirPlay2Credentials.generate(), includeScreenCaptureAcl = true)
            )
        )

        // OPACK: dict with one entry (0xE1), 23-byte string (0x40 + 23), key, true (0x01).
        val expectedAcl = byteArrayOf(0xE1.toByte(), 0x57) +
            "com.apple.ScreenCapture".toByteArray(Charsets.UTF_8) + byteArrayOf(0x01)
        assertArrayEquals(expectedAcl, sub[TlvUtil.TLV_ACL]?.single())
    }

    @Test
    fun `verifyM6 fails only on an error TLV`() {
        val srp = SRP6aClient("3939")
        assertTrue(srp.verifyM6(TlvUtil.build(TlvUtil.TLV_STATE to byteArrayOf(6))))
        assertFalse(
            srp.verifyM6(
                TlvUtil.build(
                    TlvUtil.TLV_STATE to byteArrayOf(6),
                    TlvUtil.TLV_ERROR to byteArrayOf(2)
                )
            )
        )
    }

    private fun completedExchange(): Pair<ReferenceSrpServer.Session, SRP6aClient> {
        val server = ReferenceSrpServer("3939", seededRandom(1))
        val srp = SRP6aClient("3939", random = seededRandom(2))
        val m3 = TlvUtil.parse(checkNotNull(srp.processM2(server.m2())))
        val session = server.accept(m3.getValue(TlvUtil.TLV_PUBLIC_KEY).single())
        assertTrue(srp.verifyM4(session.m4()))
        return session to srp
    }

    private fun seededRandom(seed: Long): SecureRandom =
        SecureRandom.getInstance("SHA1PRNG").apply {
            setSeed(seed)
        }
}

/**
 * Receiver side of HAP pair-setup. BouncyCastle's [SRP6Server] supplies the RFC 5054
 * math (verifier from x = H(s | H(I ":" P)), padded k and u, S = (A v^u)^b); the HAP
 * framing on top (K = H(S), M1 layout, M4 = H(A | M1 | K), M5 decryption) follows the
 * HAP spec independently of [SRP6aClient].
 */
private class ReferenceSrpServer(pin: String, random: SecureRandom) {
    private val group = SRP6StandardGroups.rfc5054_3072
    private val salt = ByteArray(16).also(random::nextBytes)
    private val server = SRP6Server().apply {
        val verifier = SRP6VerifierGenerator()
            .apply { init(group, SHA512Digest()) }
            .generateVerifier(salt, USERNAME, pin.toByteArray(Charsets.UTF_8))
        init(group, verifier, SHA512Digest(), random)
    }
    private val serverB = server.generateServerCredentials()

    fun m2(): ByteArray = TlvUtil.build(
        TlvUtil.TLV_STATE to byteArrayOf(2),
        TlvUtil.TLV_SALT to salt,
        TlvUtil.TLV_PUBLIC_KEY to unsigned(serverB)
    )

    fun accept(wireA: ByteArray): Session {
        val clientA = BigInteger(1, wireA)
        val sessionK = sha512(unsigned(server.calculateSecret(clientA)))
        val hnXorHg = sha512(unsigned(group.n)).zip(sha512(unsigned(group.g))) { a, b ->
            (a.toInt() xor b.toInt()).toByte()
        }.toByteArray()
        val proofM1 = sha512(
            hnXorHg + sha512(USERNAME) + salt + unsigned(clientA) + unsigned(serverB) + sessionK
        )
        return Session(unsigned(clientA), sessionK, proofM1)
    }

    class Session(
        private val clientA: ByteArray,
        val sessionKeyK: ByteArray,
        val clientProofM1: ByteArray
    ) {
        fun serverProof(): ByteArray = sha512(clientA + clientProofM1 + sessionKeyK)

        fun m4(): ByteArray = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(4),
            TlvUtil.TLV_PROOF to serverProof()
        )

        fun controllerSignKey(): ByteArray = hkdf("Pair-Setup-Controller-Sign")

        /** Decrypts M5 the way the receiver does and returns the inner TLV. */
        fun openM5(m5: ByteArray): Map<Int, List<ByteArray>> {
            val outer = TlvUtil.parse(m5)
            assertArrayEquals(byteArrayOf(5), outer[TlvUtil.TLV_STATE]?.firstOrNull())
            val sealed = TlvUtil.getFirst(m5, TlvUtil.TLV_ENCRYPTED_DATA)!!
            val nonce = ByteArray(4) + "PS-Msg05".toByteArray(Charsets.UTF_8)
            val plain = AirPlay2Crypto.chacha20Poly1305Decrypt(
                hkdf("Pair-Setup-Encrypt"),
                nonce,
                sealed.copyOfRange(0, sealed.size - 16),
                ByteArray(0),
                sealed.copyOfRange(sealed.size - 16, sealed.size)
            )
            return TlvUtil.parse(plain)
        }

        private fun hkdf(label: String): ByteArray = AirPlay2Crypto.hkdfSha512(
            "$label-Salt".toByteArray(Charsets.UTF_8),
            sessionKeyK,
            "$label-Info".toByteArray(Charsets.UTF_8),
            32
        )
    }

    private companion object {
        val USERNAME = "Pair-Setup".toByteArray(Charsets.UTF_8)
    }
}

private fun sha512(data: ByteArray): ByteArray = SHA512Digest().run {
    update(data, 0, data.size)
    ByteArray(digestSize).also { doFinal(it, 0) }
}

private fun unsigned(n: BigInteger): ByteArray = n.toByteArray().let {
    if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
}
