package com.creolecast.app.airplay2

import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.digests.SHA512Digest

/**
 * SRP-6a (RFC 5054 3072-bit group, SHA-512) as HomeKit/AirPlay 2 uses it for
 * `/pair-setup`. Transcribed from the Go reference implementation used by the
 * F-Droid app "Mirror": doubletake `internal/airplay/pairing.go`, functions
 * `pairSetupTransient` (M1) and `completeSRPExchange` (M3..M6).
 * Names carry the RFC 5054 symbol (`N`, `g`, `clientA`, `serverB`, `sessionK`,
 * `proofM1`, ...) so the code can be checked against the spec; the public group
 * constants keep the spec spelling `N`/`g`, hence the naming suppression.
 */
@Suppress("ktlint:standard:property-naming")
class SRP6aClient(
    private val pin: String,
    private val username: String = "Pair-Setup",
    private val random: SecureRandom = SecureRandom()
) {
    companion object {
        val N: BigInteger = SRP6StandardGroups.rfc5054_3072.n
        val g: BigInteger = SRP6StandardGroups.rfc5054_3072.g
        private const val PUBKEY_3072_SIZE = 384

        // HAP fixes the pair-setup salt at 16 bytes.
        private const val PAIR_SETUP_SALT_SIZE = 16
        private const val PRIVATE_EXPONENT_SIZE = 32
        private const val SESSION_KEY_SIZE = 32
        private const val CHACHA_NONCE_SIZE = 12

        private const val STATE_M2: Byte = 2
        private const val STATE_M3: Byte = 3
        private const val STATE_M5: Byte = 5

        /**
         * Flags TLV for transient pairing: the single byte 0x10. HAP TLV integers are
         * minimal-length, and shairport-sync's pair_ap only recognises transient when
         * the value is exactly one byte; a 4-byte uint32 is treated as full pairing.
         */
        private val TRANSIENT_FLAGS = byteArrayOf(0x10)

        /** OPACK `{"com.apple.ScreenCapture": true}` (pairing.go screenCaptureACL). */
        private val SCREEN_CAPTURE_ACL =
            byteArrayOf(
                0xE1.toByte(),
                0x57
            ) + "com.apple.ScreenCapture".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0x01)
    }

    /** Values from M3 that the M4 proof check needs (RFC 5054 A, K and M1). */
    private class M3Proof(
        val clientPublicA: ByteArray,
        val sessionKeyK: ByteArray,
        val clientProofM1: ByteArray
    )

    private val digest = SHA512Digest()
    private val digestSize = digest.digestSize

    private var m3Proof: M3Proof? = null
    var sharedKeyBytes: ByteArray? = null
        private set

    /**
     * M1. Transient pairing additionally carries the flags TLV; without it the
     * receiver treats the exchange as a full PIN pairing and rejects the fixed
     * transient password.
     */
    fun buildM1(transient: Boolean): ByteArray = if (transient) {
        TlvUtil.build(
            TlvUtil.TLV_METHOD to byteArrayOf(0),
            TlvUtil.TLV_STATE to byteArrayOf(1),
            TlvUtil.TLV_FLAGS to TRANSIENT_FLAGS
        )
    } else {
        TlvUtil.build(
            TlvUtil.TLV_METHOD to byteArrayOf(0),
            TlvUtil.TLV_STATE to byteArrayOf(1)
        )
    }

    fun processM2(m2Tlv: ByteArray): ByteArray? {
        val parsed = TlvUtil.parse(m2Tlv)
        val state = parsed[TlvUtil.TLV_STATE]?.firstOrNull()?.get(0)
        // HAP fixes the pair-setup salt at 16 bytes; any other length means the
        // peer is not speaking this protocol and the proof would be garbage.
        val salt = parsed[TlvUtil.TLV_SALT]?.firstOrNull()
            ?.takeIf { it.size == PAIR_SETUP_SALT_SIZE }
        val serverB = parsed[TlvUtil.TLV_PUBLIC_KEY]?.firstOrNull()
            ?.let { BigInteger(1, it) }
            ?.takeIf(::isAcceptableServerPublic)
        if (state != STATE_M2 || salt == null || serverB == null) return null
        return buildM3(salt, serverB)
    }

    /**
     * RFC 5054 §3.1: abort if B mod N == 0 - a malicious/compromised peer could pick
     * a degenerate B to make the shared secret predictable and bypass proof of
     * knowledge of the password.
     */
    private fun isAcceptableServerPublic(serverB: BigInteger): Boolean =
        serverB.signum() > 0 && serverB < N

    private fun buildM3(salt: ByteArray, serverB: BigInteger): ByteArray {
        val passwordBytes = pin.toByteArray(Charsets.UTF_8)
        val usernameBytes = username.toByteArray(Charsets.UTF_8)

        val innerHash = hash(usernameBytes + byteArrayOf(':'.code.toByte()) + passwordBytes)
        val xBytes = hash(salt + innerHash)
        val x = BigInteger(1, xBytes)

        val aBytes = ByteArray(PRIVATE_EXPONENT_SIZE).also(random::nextBytes)
        val a = BigInteger(1, aBytes).let { if (it.signum() == 0) BigInteger.ONE else it }
        val clientA = g.modPow(a, N)
        val clientARaw = bigIntToMinimal(clientA)
        val serverBRaw = bigIntToMinimal(serverB)

        // k = H(pad384(N) || pad384(g))
        val kBytes = hash(bigIntToFixed(N, PUBKEY_3072_SIZE) + bigIntToFixed(g, PUBKEY_3072_SIZE))
        val k = BigInteger(1, kBytes)

        // u = H(pad384(A) || pad384(B))
        val uBytes = hash(
            bigIntToFixed(clientA, PUBKEY_3072_SIZE) + bigIntToFixed(serverB, PUBKEY_3072_SIZE)
        )
        val u = BigInteger(1, uBytes)

        // S = (B - k * g^x) ^ (a + u * x) mod N
        val gx = g.modPow(x, N)
        val kgx = k.multiply(gx).mod(N)
        val base = serverB.subtract(kgx).mod(N)
        val exponent = a.add(u.multiply(x))
        val premasterS = base.modPow(exponent, N)

        val sessionK = hash(bigIntToMinimal(premasterS))
        this.sharedKeyBytes = sessionK

        val hn = hash(bigIntToMinimal(N))
        val hg = hash(bigIntToMinimal(g))
        val hnXorHg = xorBytes(hn, hg)
        val hi = hash(usernameBytes)

        // Proof uses natural (unpadded) A and B, but the wire TLV carries A padded
        // to the 384-byte group size (pairing.go:594).
        val proofM1 = hash(hnXorHg + hi + salt + clientARaw + serverBRaw + sessionK)
        this.m3Proof = M3Proof(clientARaw, sessionK, proofM1)

        return TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(STATE_M3),
            TlvUtil.TLV_PUBLIC_KEY to bigIntToFixed(clientA, PUBKEY_3072_SIZE),
            TlvUtil.TLV_PROOF to proofM1
        )
    }

    /**
     * M4. Mirrors pairing.go:602-614: an error TLV fails, a present proof must
     * match, and an absent proof is accepted.
     */
    fun verifyM4(m4Tlv: ByteArray): Boolean {
        val parsed = TlvUtil.parse(m4Tlv)
        val serverProof = parsed[TlvUtil.TLV_PROOF]?.firstOrNull()
        return when {
            !parsed[TlvUtil.TLV_ERROR].isNullOrEmpty() -> false
            serverProof == null -> true
            else -> serverProofMatches(serverProof)
        }
    }

    /** M2 = H(A || M1 || K); false when no M3 was built. */
    private fun serverProofMatches(serverProof: ByteArray): Boolean {
        val proof = m3Proof ?: return false
        val expectedM2 = hash(proof.clientPublicA + proof.clientProofM1 + proof.sessionKeyK)
        return expectedM2.contentEquals(serverProof)
    }

    /**
     * M5: hand the receiver our long-term Ed25519 identity, signed with that
     * same identity. The key pair MUST be the persisted one that pair-verify
     * will later sign with (pairing.go:619-650).
     */
    fun buildM5(credentials: AirPlay2Credentials, includeScreenCaptureAcl: Boolean): ByteArray? {
        val k = sharedKeyBytes ?: return null
        val encKey = AirPlay2Crypto.hkdfSha512(
            "Pair-Setup-Encrypt-Salt".toByteArray(Charsets.UTF_8),
            k,
            "Pair-Setup-Encrypt-Info".toByteArray(Charsets.UTF_8),
            SESSION_KEY_SIZE
        )
        val sigKey = AirPlay2Crypto.hkdfSha512(
            "Pair-Setup-Controller-Sign-Salt".toByteArray(Charsets.UTF_8),
            k,
            "Pair-Setup-Controller-Sign-Info".toByteArray(Charsets.UTF_8),
            SESSION_KEY_SIZE
        )

        val identifier = credentials.pairingId.toByteArray(Charsets.UTF_8)
        val signData = sigKey + identifier + credentials.ed25519Public
        val signature = AirPlay2Crypto.ed25519SignWithSeed(credentials.ed25519Seed, signData)

        val subTlv = if (includeScreenCaptureAcl) {
            TlvUtil.build(
                TlvUtil.TLV_IDENTIFIER to identifier,
                TlvUtil.TLV_PUBLIC_KEY to credentials.ed25519Public,
                TlvUtil.TLV_SIGNATURE to signature,
                TlvUtil.TLV_ACL to SCREEN_CAPTURE_ACL
            )
        } else {
            TlvUtil.build(
                TlvUtil.TLV_IDENTIFIER to identifier,
                TlvUtil.TLV_PUBLIC_KEY to credentials.ed25519Public,
                TlvUtil.TLV_SIGNATURE to signature
            )
        }

        // Fixed nonce: 00 00 00 00 "PS-Msg05"
        val nonceLabel = "PS-Msg05".toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(CHACHA_NONCE_SIZE)
        nonceLabel.copyInto(nonce, destinationOffset = CHACHA_NONCE_SIZE - nonceLabel.size)

        val (ciphertext, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
            encKey,
            nonce,
            subTlv,
            ByteArray(0)
        )

        return TlvUtil.build(
            TlvUtil.TLV_ENCRYPTED_DATA to (ciphertext + tag),
            TlvUtil.TLV_STATE to byteArrayOf(STATE_M5)
        )
    }

    /**
     * M6. The reference only checks for an error TLV and never decrypts the
     * body (pairing.go:656-661); the accessory's long-term public key is taken
     * from `/info` instead. Returns false only on an explicit error TLV.
     */
    fun verifyM6(m6Tlv: ByteArray): Boolean {
        val parsed = TlvUtil.parse(m6Tlv)
        return parsed[TlvUtil.TLV_ERROR].isNullOrEmpty()
    }

    private fun hash(data: ByteArray): ByteArray {
        val out = ByteArray(digestSize)
        digest.reset()
        digest.update(data, 0, data.size)
        digest.doFinal(out, 0)
        return out
    }
}

private fun bigIntToMinimal(n: BigInteger): ByteArray {
    val bytes = n.toByteArray()
    return if (bytes.size > 1 && bytes[0] == 0.toByte()) {
        bytes.copyOfRange(1, bytes.size)
    } else {
        bytes
    }
}

private fun xorBytes(a: ByteArray, b: ByteArray): ByteArray {
    val out = ByteArray(minOf(a.size, b.size))
    for (i in out.indices) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
    return out
}

private fun bigIntToFixed(n: BigInteger, size: Int): ByteArray {
    val minimal = bigIntToMinimal(n)
    if (minimal.size >= size) return minimal.copyOfRange(minimal.size - size, minimal.size)
    val out = ByteArray(size)
    System.arraycopy(minimal, 0, out, size - minimal.size, minimal.size)
    return out
}
