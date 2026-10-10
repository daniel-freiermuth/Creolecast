package com.creolecast.app.airplay2

import android.util.Log
import java.net.HttpURLConnection
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/**
 * HAP pair-verify (pairing.go `hapPairVerify`). M3 is signed with the
 * persisted long-term key — the same one pair-setup M5 registered.
 */
internal class HapPairVerify(
    private val session: PairingSession,
    private val control: AirPlay2ControlChannel,
    private val receiver: AirPlay2ReceiverInfo
) {
    private companion object {
        const val TAG = "AirPlay2Client"
        const val PAIR_VERIFY_PATH = "/pair-verify"
        const val STATE_M1: Byte = 1
        const val STATE_M3: Byte = 3
        const val VERIFY_KEY_SIZE = 32
        const val ED25519_PUBLIC_KEY_SIZE = 32
    }

    /** The receiver's M2: its ephemeral key and the sealed proof of its identity. */
    private class VerifyM2(val serverPublicKey: ByteArray, val encryptedData: ByteArray)

    /** Both ephemeral keys and the session key derived from them. */
    private class VerifyKeys(
        val clientPublicKey: ByteArray,
        val serverPublicKey: ByteArray,
        val verifyKey: ByteArray
    )

    fun run(credentials: AirPlay2Credentials): Boolean {
        val keyPair = AirPlay2Crypto.generateCurve25519KeyPair()
        val clientPub = AirPlay2Crypto.getPublicKeyBytes(keyPair)
        val m2 = requestM2(clientPub) ?: return false

        val shared = AirPlay2Crypto.curve25519Agree(
            keyPair.private as X25519PrivateKeyParameters,
            m2.serverPublicKey
        )
        val keys = VerifyKeys(
            clientPublicKey = clientPub,
            serverPublicKey = m2.serverPublicKey,
            verifyKey = AirPlay2Crypto.hkdfSha512(
                "Pair-Verify-Encrypt-Salt".toByteArray(Charsets.UTF_8),
                shared,
                "Pair-Verify-Encrypt-Info".toByteArray(Charsets.UTF_8),
                VERIFY_KEY_SIZE
            )
        )
        val authenticated = decryptM2(keys, m2.encryptedData)
            ?.let { authenticateReceiver(it, keys) } == true
        val verified = authenticated && sendM3(credentials, keys)
        if (verified) {
            control.enableEncryption(shared)
            Log.d(TAG, "Pair-verify complete, control channel encrypted")
        }
        return verified
    }

    private fun requestM2(clientPub: ByteArray): VerifyM2? {
        val m1 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(STATE_M1),
            TlvUtil.TLV_PUBLIC_KEY to clientPub
        )
        val resp = session.post(PAIR_VERIFY_PATH, m1, verify = true)
        val body = resp.body
        return when {
            resp.code != HttpURLConnection.HTTP_OK -> {
                Log.e(TAG, "pair-verify M1 failed: ${resp.code}")
                null
            }

            body == null -> null

            else -> parseM2(body)
        }
    }

    private fun parseM2(body: ByteArray): VerifyM2? {
        val parsed = TlvUtil.parse(body)
        if (!parsed[TlvUtil.TLV_ERROR].isNullOrEmpty()) {
            Log.e(TAG, "pair-verify M2 error")
            return null
        }
        val serverPubKey = parsed[TlvUtil.TLV_PUBLIC_KEY]?.firstOrNull()
        val encryptedData = parsed[TlvUtil.TLV_ENCRYPTED_DATA]?.firstOrNull()
        val complete = serverPubKey != null &&
            encryptedData != null &&
            encryptedData.size >= WireBytes.AEAD_TAG_SIZE
        return if (complete) VerifyM2(serverPubKey, encryptedData) else null
    }

    private fun decryptM2(keys: VerifyKeys, encryptedData: ByteArray): ByteArray? {
        val tagStart = encryptedData.size - WireBytes.AEAD_TAG_SIZE
        return try {
            AirPlay2Crypto.chacha20Poly1305Decrypt(
                keys.verifyKey,
                WireBytes.labelNonce("PV-Msg02"),
                encryptedData.copyOfRange(0, tagStart),
                ByteArray(0),
                encryptedData.copyOfRange(tagStart, encryptedData.size)
            )
        } catch (e: InvalidCipherTextException) {
            // A tag mismatch means the peer did not derive the same shared
            // secret, so the exchange is already unauthenticated — fail hard.
            Log.e(TAG, "pair-verify M2 decrypt failed", e)
            null
        }
    }

    /**
     * Authenticate the receiver when /info or the mDNS TXT record gave us its
     * long-term key. The reference skips this because it keeps no accessory
     * key; we have one, so we enforce it.
     */
    private fun authenticateReceiver(decrypted: ByteArray, keys: VerifyKeys): Boolean {
        val serverLtpk = receiver.publicKey?.takeIf { it.size == ED25519_PUBLIC_KEY_SIZE }
            ?: return true
        val inner = TlvUtil.parse(decrypted)
        val serverId = inner[TlvUtil.TLV_IDENTIFIER]?.firstOrNull()
        val serverSig = inner[TlvUtil.TLV_SIGNATURE]?.firstOrNull()
        return when {
            serverId == null || serverSig == null -> {
                Log.e(TAG, "pair-verify M2 missing identifier/signature")
                false
            }

            !AirPlay2Crypto.ed25519Verify(
                serverLtpk,
                keys.serverPublicKey + serverId + keys.clientPublicKey,
                serverSig
            ) -> {
                Log.e(TAG, "pair-verify M2 server signature rejected")
                false
            }

            else -> true
        }
    }

    private fun sendM3(credentials: AirPlay2Credentials, keys: VerifyKeys): Boolean {
        val identifier = credentials.pairingId.toByteArray(Charsets.UTF_8)
        val signature = AirPlay2Crypto.ed25519SignWithSeed(
            credentials.ed25519Seed,
            keys.clientPublicKey + identifier + keys.serverPublicKey
        )
        val inner = TlvUtil.build(
            TlvUtil.TLV_IDENTIFIER to identifier,
            TlvUtil.TLV_SIGNATURE to signature
        )
        val (ciphertext, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
            keys.verifyKey,
            WireBytes.labelNonce("PV-Msg03"),
            inner,
            ByteArray(0)
        )
        val m3 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(STATE_M3),
            TlvUtil.TLV_ENCRYPTED_DATA to (ciphertext + tag)
        )
        val resp = session.post(PAIR_VERIFY_PATH, m3, verify = true)
        val accepted = resp.code == HttpURLConnection.HTTP_OK
        val m4Error = accepted && resp.body?.let {
            it.isNotEmpty() && !TlvUtil.parse(it)[TlvUtil.TLV_ERROR].isNullOrEmpty()
        } == true
        when {
            !accepted -> Log.e(TAG, "pair-verify M3 failed: ${resp.code}")
            m4Error -> Log.e(TAG, "pair-verify M4 error")
        }
        return accepted && !m4Error
    }
}
