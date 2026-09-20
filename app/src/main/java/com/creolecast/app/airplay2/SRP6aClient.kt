package com.creolecast.app.airplay2

import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.digests.SHA512Digest
import java.math.BigInteger
import java.security.SecureRandom

/**
 * SRP-6a (RFC 5054 3072-bit group, SHA-512) as HomeKit/AirPlay 2 uses it for
 * `/pair-setup`. Transcribed from the Go reference implementation used by the
 * F-Droid app "Mirror": doubletake `internal/airplay/pairing.go`, functions
 * `pairSetupTransient` (M1) and `completeSRPExchange` (M3..M6).
 */
class SRP6aClient(
    private val pin: String,
    private val username: String = "Pair-Setup",
    private val random: SecureRandom = SecureRandom()
) {
    companion object {
        val N: BigInteger = SRP6StandardGroups.rfc5054_3072.n
        val g: BigInteger = SRP6StandardGroups.rfc5054_3072.g
        private const val PUBKEY_3072_SIZE = 384

        /** tlvFlags value for transient pairing: uint32 LE 0x00000010 (pairing.go:339-340). */
        private val TRANSIENT_FLAGS = byteArrayOf(0x10, 0x00, 0x00, 0x00)

        /** OPACK `{"com.apple.ScreenCapture": true}` (pairing.go screenCaptureACL). */
        private val SCREEN_CAPTURE_ACL =
            byteArrayOf(0xE1.toByte(), 0x57) + "com.apple.ScreenCapture".toByteArray(Charsets.UTF_8) + byteArrayOf(0x01)
    }

    private val digest = SHA512Digest()
    private val digestSize = digest.digestSize

    private var a: BigInteger? = null
    private var A: BigInteger? = null
    private var Araw: ByteArray? = null
    private var x: BigInteger? = null
    private var S: BigInteger? = null
    private var K: ByteArray? = null
    private var B: BigInteger? = null
    private var M1: ByteArray? = null
    var sharedKeyBytes: ByteArray? = null
        private set
    private var salt: ByteArray? = null

    /**
     * M1. Transient pairing additionally carries the flags TLV; without it the
     * receiver treats the exchange as a full PIN pairing and rejects the empty
     * password (pairing.go:337-352).
     */
    fun buildM1(transient: Boolean): ByteArray {
        return if (transient) {
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
    }

    fun processM2(m2Tlv: ByteArray): ByteArray? {
        val parsed = TlvUtil.parse(m2Tlv)
        val state = parsed[TlvUtil.TLV_STATE]?.firstOrNull()?.get(0) ?: return null
        if (state != 2.toByte()) return null
        val salt = parsed[TlvUtil.TLV_SALT]?.firstOrNull() ?: return null
        val serverB = parsed[TlvUtil.TLV_PUBLIC_KEY]?.firstOrNull() ?: return null
        val bValue = BigInteger(1, serverB)
        // RFC 5054 §3.1: abort if B mod N == 0 - a malicious/compromised peer could pick
        // a degenerate B to make the shared secret predictable and bypass proof of
        // knowledge of the password.
        if (bValue.signum() <= 0 || bValue >= N) return null
        this.salt = salt
        this.B = bValue
        return buildM3(salt, serverB)
    }

    private fun buildM3(salt: ByteArray, serverB: ByteArray): ByteArray {
        val passwordBytes = pin.toByteArray(Charsets.UTF_8)
        val usernameBytes = username.toByteArray(Charsets.UTF_8)

        val innerHash = hash(usernameBytes + byteArrayOf(':'.code.toByte()) + passwordBytes)
        val xBytes = hash(salt + innerHash)
        this.x = BigInteger(1, xBytes)
        val xv = this.x!!

        val aBytes = ByteArray(32).also(random::nextBytes)
        val av = BigInteger(1, aBytes).let { if (it.signum() == 0) BigInteger.ONE else it }
        this.a = av
        val Av = g.modPow(av, N)
        this.A = Av
        this.Araw = bigIntToMinimal(Av)
        val Araw = this.Araw!!

        val Bv = this.B!!
        val Bravo = bigIntToMinimal(Bv)

        // k = H(pad384(N) || pad384(g))
        val kBytes = hash(bigIntToFixed(N, PUBKEY_3072_SIZE) + bigIntToFixed(g, PUBKEY_3072_SIZE))
        val k = BigInteger(1, kBytes)

        // u = H(pad384(A) || pad384(B))
        val uBytes = hash(bigIntToFixed(Av, PUBKEY_3072_SIZE) + bigIntToFixed(Bv, PUBKEY_3072_SIZE))
        val u = BigInteger(1, uBytes)

        val gx = g.modPow(xv, N)
        val kgx = k.multiply(gx).mod(N)
        val base = Bv.subtract(kgx).mod(N)
        val exponent = av.add(u.multiply(xv))
        val Sv = base.modPow(exponent, N)
        this.S = Sv

        val Kv = hash(bigIntToMinimal(Sv))
        this.K = Kv
        this.sharedKeyBytes = Kv

        val hn = hash(bigIntToMinimal(N))
        val hg = hash(bigIntToMinimal(g))
        val hnXorHg = xorBytes(hn, hg)
        val hi = hash(usernameBytes)

        // Proof uses natural (unpadded) A and B, but the wire TLV carries A padded
        // to the 384-byte group size (pairing.go:594).
        val M1v = hash(hnXorHg + hi + salt + Araw + Bravo + Kv)
        this.M1 = M1v

        return TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(3),
            TlvUtil.TLV_PUBLIC_KEY to bigIntToFixed(Av, PUBKEY_3072_SIZE),
            TlvUtil.TLV_PROOF to M1v
        )
    }

    /**
     * M4. Mirrors pairing.go:602-614: an error TLV fails, a present proof must
     * match, and an absent proof is accepted.
     */
    fun verifyM4(m4Tlv: ByteArray): Boolean {
        val parsed = TlvUtil.parse(m4Tlv)
        if (parsed[TlvUtil.TLV_ERROR]?.firstOrNull() != null) return false
        val serverProof = parsed[TlvUtil.TLV_PROOF]?.firstOrNull() ?: return true

        val m1 = this.M1 ?: return false
        val k = this.K ?: return false
        val expectedM2 = hash(this.Araw!! + m1 + k)
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
            32
        )
        val sigKey = AirPlay2Crypto.hkdfSha512(
            "Pair-Setup-Controller-Sign-Salt".toByteArray(Charsets.UTF_8),
            k,
            "Pair-Setup-Controller-Sign-Info".toByteArray(Charsets.UTF_8),
            32
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
        val nonce = ByteArray(12)
        System.arraycopy("PS-Msg05".toByteArray(Charsets.UTF_8), 0, nonce, 4, 8)

        val (ciphertext, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(encKey, nonce, subTlv, ByteArray(0))

        return TlvUtil.build(
            TlvUtil.TLV_ENCRYPTED_DATA to (ciphertext + tag),
            TlvUtil.TLV_STATE to byteArrayOf(5)
        )
    }

    /**
     * M6. The reference only checks for an error TLV and never decrypts the
     * body (pairing.go:656-661); the accessory's long-term public key is taken
     * from `/info` instead. Returns false only on an explicit error TLV.
     */
    fun verifyM6(m6Tlv: ByteArray): Boolean {
        val parsed = TlvUtil.parse(m6Tlv)
        return parsed[TlvUtil.TLV_ERROR]?.firstOrNull() == null
    }

    private fun hash(data: ByteArray): ByteArray {
        val out = ByteArray(digestSize)
        digest.reset()
        digest.update(data, 0, data.size)
        digest.doFinal(out, 0)
        return out
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

    operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(this.size + other.size)
        System.arraycopy(this, 0, result, 0, this.size)
        System.arraycopy(other, 0, result, this.size, other.size)
        return result
    }
}
