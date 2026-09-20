package com.creolecast.app.airplay2

import android.util.Log
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import kotlin.concurrent.thread

class NeedsPinException(val host: String) : Exception("PIN needed for host $host")

class AirPlay2Client(
    private val host: String,
    private val port: Int,
    private val deviceId: String,
    private val dacpId: String,
    private val activeRemote: String,
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
    private val frameSize: Int = 352,
    private val password: String? = null,
    private val txtPk: ByteArray? = null,
    private val credentialStore: AirPlay2CredentialStore = InMemoryCredentialStore(),
    private val clientName: String = "CreoleCast"
) {
    interface EventListener {
        fun onVolumeChange(db: Double) {}
        fun onRemoteCommand(command: String) {}
    }

    companion object {
        private const val TAG = "AirPlay2Client"
        private const val PUBKEY_3072_SIZE = 384
        private const val HAP_FRAME_SIZE = 1024
        private const val USER_AGENT = "AirPlay/935.7.1"
        private const val SOURCE_VERSION_NTP = "280.33"
        private const val SOURCE_VERSION_PTP = "980.71.1"

        /** Receiver feature bits. */
        private const val FEATURE_FPSAP = 14
        private const val FEATURE_PTP = 41
        private const val FEATURE_STREAM_CONNECTIONS = 59

        /** 85 ms at 44100 Hz, truncated. */
        private const val LATENCY_SAMPLES = 3748

        /** X-Apple-HKP pairing types. */
        private const val HKP_TRANSIENT = 4
        private const val HKP_SCREEN_CAPTURE = 5
    }

    var eventListener: EventListener? = null

    private val secureRandom = SecureRandom()
    private val socket = Socket()
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var sessionUrl: String = ""
    private val sessionUuid = UUID.randomUUID()
    private val streamConnectionId = secureRandom.nextLong() and 0x7FFFFFFFFFFFFFFFL
    private var cseq = 0

    private val timingSocket = DatagramSocket(0)
    private val controlSocket = DatagramSocket(0)
    private var audioSocket: DatagramSocket? = null
    private var eventSocket: Socket? = null
    private var eventPort = 0

    private var timingRemotePort = 0
    private var controlRemotePort = 0
    private var audioRemotePort = 0

    private var sequence = 0
    private var rtpTimestamp = 0
    private var syncPacketSent = false
    private val ssrc = secureRandom.nextInt().toUInt().toLong()

    private var sharedSecret: ByteArray? = null
    private var ecdhKeyPair: AsymmetricCipherKeyPair? = null
    private var cipherKeys: AirPlay2Crypto.PairKeysResult? = null
    private var supportsEncryption = false
    private var deviceEd25519PubKey: ByteArray? = null
    private var sessionId: String? = null
    private var didTransientPairing = false
    private var credentials: AirPlay2Credentials? = null

    /**
     * The long-term identity this sender presents to the receiver. Loaded from
     * the store on first use so pair-setup M5 and pair-verify M3 always sign
     * with the same key, across connections.
     */
    private fun pairingCredentials(): AirPlay2Credentials {
        credentials?.let { return it }
        val loaded = credentialStore.load(receiverCredentialKey()) ?: AirPlay2Credentials.generate()
        credentials = loaded
        return loaded
    }

    private fun receiverCredentialKey(): String = receiverDeviceId

    @Volatile private var running = false
    private var syncThread: Thread? = null
    private var keepAliveThread: Thread? = null
    private var timingThread: Thread? = null

    /** Negotiated timing protocol and the receiver's PTP clock identity. */
    private var usePtp = false
    private var ptpTimelineId = 0L
    private var useStreamConnections = false

    /** Audio stream key published as `shk`; null means audio goes out in the clear. */
    private var audioKey: ByteArray? = null
    private var audioNonceCounter = 0L

    /** HAP control-channel encryption, enabled once pair-verify completes. */
    private var channelEncrypted = false
    private var hapWriteKey: ByteArray? = null
    private var hapReadKey: ByteArray? = null
    private var hapWriteNonce = 0L
    private var hapReadNonce = 0L

    /** Receiver identity and capabilities from /info. */
    private var receiverDeviceId: String = host
    private var receiverFeatures = 0L
    private var receiverSourceVersion = ""

    private fun parseFeatures(value: Any?): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is String -> parseFeatureString(value)
        else -> 0L
    }

    /** Feature strings are either "0x1234" or "0xLOW,0xHIGH". */
    private fun parseFeatureString(text: String): Long {
        val parts = text.split(",")
        fun hex(s: String): Long = s.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: 0L
        return when (parts.size) {
            0 -> 0L
            1 -> hex(parts[0])
            else -> (hex(parts[1]) shl 32) or (hex(parts[0]) and 0xFFFFFFFFL)
        }
    }

    private fun hasFeature(bit: Int): Boolean = (receiverFeatures shr bit) and 1L == 1L

    /** PTP needs SourceVersion >= 354.54.6; 377.40.x advertises it but is broken. */
    private fun supportsPtpSourceVersion(version: String): Boolean {
        if (version.isEmpty()) return false
        val parts = version.split(".").map { it.toIntOrNull() ?: return false }
        if (parts.size >= 2 && parts[0] == 377 && parts[1] == 40) return false
        val minimum = listOf(354, 54, 6)
        for (i in minimum.indices) {
            val actual = parts.getOrElse(i) { 0 }
            if (actual > minimum[i]) return true
            if (actual < minimum[i]) return false
        }
        return true
    }

    class HttpResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray?)

    fun connect(): Boolean {
        try {
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.tcpNoDelay = true
            socket.soTimeout = 10000
            output = socket.getOutputStream()
            input = HapInputStream(socket.getInputStream())
            // The session is addressed by the receiver's own host and port plus
            // the stream connection id, not by the sender's local address.
            sessionUrl = "rtsp://$host:$port/$streamConnectionId"
            sequence = secureRandom.nextInt(0xFFFF)
            rtpTimestamp = secureRandom.nextInt()

            if (txtPk != null) {
                deviceEd25519PubKey = txtPk
                Log.d(TAG, "Device pk from TXT, length=${txtPk.size}")
            }

            val info = getInfo() ?: return false
            val statusFlags = info["statusFlags"] as? Long ?: 0L
            (info["deviceID"] as? String)?.let { if (it.isNotEmpty()) receiverDeviceId = it }
            receiverFeatures = parseFeatures(info["features"])
            receiverSourceVersion = info["sourceVersion"] as? String ?: ""

            if (deviceEd25519PubKey == null && info["pk"] != null) {
                val pkRaw = info["pk"]
                deviceEd25519PubKey = when (pkRaw) {
                    is ByteArray -> pkRaw
                    is String -> try {
                        android.util.Base64.decode(pkRaw, android.util.Base64.NO_WRAP)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                    else -> null
                }
            }
            Log.d(TAG, "Receiver $receiverDeviceId flags=$statusFlags " +
                "features=0x${receiverFeatures.toString(16)} src=$receiverSourceVersion " +
                "hasPk=${deviceEd25519PubKey != null}")

            // If the device needs pairing, do pair-setup
            // Transient: no password → default to "3939" (matches owntone - see pair_homekit.c:1177)
            // Full: password provided → use as-is
            val effectivePassword = password
            if (effectivePassword != null || needsPairing(statusFlags)) {
                Log.d(TAG, "Pair-setup: starting")
                if (!doPairSetup(effectivePassword)) {
                    if (password == null) {
                        throw NeedsPinException(host)
                    }
                    return false
                }
            }

            // Pair-verify always runs: it is what derives the session keys and
            // proves we hold the identity M5 registered.
            if (!doPairVerify()) {
                Log.e(TAG, "pair-verify failed")
                return false
            }

            // PTP needs an encrypted session, feature bit 41 and a new enough
            // receiver; everything else falls back to NTP.
            usePtp = channelEncrypted && hasFeature(FEATURE_PTP) &&
                supportsPtpSourceVersion(receiverSourceVersion)
            Log.d(TAG, "Negotiated timing=${if (usePtp) "PTP" else "NTP"}")

            if (hasFeature(FEATURE_FPSAP) && !doFairPlaySetup()) return false

            if (!sendSetupSession()) return false
            if (!sendRecord()) return false
            connectEventPort()
            if (!sendSetupStream()) return false

            setVolume(0.0)

            running = true
            if (!usePtp) startTimingResponder()
            startSyncLoop()
            startKeepAliveLoop()
            Log.d(TAG, "AirPlay 2 connected successfully")
            return true
        } catch (e: NeedsPinException) {
            close()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            close()
            return false
        }
    }

    private fun needsPairing(statusFlags: Long): Boolean {
        val bit2 = (statusFlags shr 2) and 1
        val bit3 = (statusFlags shr 3) and 1
        val bit9 = (statusFlags shr 9) and 1
        return (bit2 or bit3 or bit9) != 0L
    }

    private fun getInfo(): Map<String, Any>? {
        val resp = sendHttpRequest("GET", "/info", null, null)
        if (resp.code != 200) return null
        val body = resp.body ?: return null
        Log.d(TAG, "GET /info body size=${body.size}, magic=${body.copyOf(8).toString(Charsets.UTF_8)}")
        return try {
            val result = BinaryPlist.decode(body)
            Log.d(TAG, "Binary plist parsed, keys=${result.keys.joinToString(",")}")
            if (result.containsKey("pk")) {
                val pkVal = result["pk"]
                Log.d(TAG, "pk type=${pkVal?.javaClass?.simpleName}, value=${if (pkVal is ByteArray) "ByteArray(${pkVal.size})" else pkVal}")
            }
            if (result.containsKey("statusFlags")) {
                Log.d(TAG, "statusFlags=${result["statusFlags"]}")
            }
            result.toMutableMap() as Map<String, Any>
        } catch (e: Exception) {
            Log.e(TAG, "Binary plist parse failed: ${e.message}", e)
            val bodyStr = body.toString(Charsets.UTF_8)
            parseXmlPlist(bodyStr)
        }
    }

    private fun parseXmlPlist(xml: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val statusRegex = Regex("<key>statusFlags</key>\\s*<integer>(\\d+)</integer>", RegexOption.DOT_MATCHES_ALL)
        val pkRegex = Regex("<key>pk</key>\\s*<data>\\s*([A-Za-z0-9+/=]+)\\s*</data>", RegexOption.DOT_MATCHES_ALL)
        val nameRegex = Regex("<key>name</key>\\s*<string>([^<]*)</string>", RegexOption.DOT_MATCHES_ALL)
        val modelRegex = Regex("<key>model</key>\\s*<string>([^<]*)</string>", RegexOption.DOT_MATCHES_ALL)
        statusRegex.find(xml)?.let { result["statusFlags"] = it.groupValues[1].toLong() }
        pkRegex.find(xml)?.let { result["pk"] = it.groupValues[1].replace(Regex("\\s+"), "") }
        nameRegex.find(xml)?.let { result["name"] = it.groupValues[1] }
        modelRegex.find(xml)?.let { result["model"] = it.groupValues[1] }
        return result
    }

    private fun doPairSetup(effectivePassword: String?): Boolean {
        // Always try transient (HKP 4) first: this covers devices that show a PIN on screen
        // (statusFlags bit 9). Fall back to full (HKP 3) only if transient is rejected.
        // Without a password, skip full pairing (nothing to authenticate with).
        for (attemptTransient in listOf(true, false)) {
            // Transient pairing authenticates with an empty password; the PIN
            // is only meaningful for full pairing.
            val attemptPin = if (attemptTransient) "" else (effectivePassword ?: return false)
            Log.d(TAG, "Pair-setup attempt: transient=$attemptTransient")
            val srp = SRP6aClient(attemptPin, "Pair-Setup", secureRandom)
            val m1 = srp.buildM1(attemptTransient)

            val hkpType = if (attemptTransient) HKP_TRANSIENT else HKP_SCREEN_CAPTURE
            val resp1 = sendPairingRequest("POST", "/pair-setup", "application/pairing+tlv8", m1, hkpType)
            Log.d(TAG, "Pair-setup M1 response: ${resp1.code}")
            if (resp1.code == 200) {
                val m3 = srp.processM2(resp1.body ?: return false) ?: return false
                val resp2 = sendPairingRequest("POST", "/pair-setup", "application/pairing+tlv8", m3, hkpType)
                if (resp2.code != 200) { Log.e(TAG, "pair-setup M3 failed: ${resp2.code}"); continue }

                if (!srp.verifyM4(resp2.body ?: return false)) {
                    Log.e(TAG, "pair-setup M4 verification failed"); continue
                }

                // M5/M6 run for transient pairing too: M5 is what registers the
                // long-term key that pair-verify then signs with. Only the
                // persistence differs - a transient pairing is forgotten by the
                // receiver when the session ends, so saving it would guarantee a
                // failed pair-verify next time.
                val creds = pairingCredentials()
                val m5 = srp.buildM5(creds, includeScreenCaptureAcl = hkpType == HKP_SCREEN_CAPTURE) ?: continue
                val resp3 = sendPairingRequest("POST", "/pair-setup", "application/pairing+tlv8", m5, hkpType)
                if (resp3.code != 200) { Log.e(TAG, "pair-setup M5 failed: ${resp3.code}"); continue }
                srp.verifyM6(resp3.body ?: return false)?.let { deviceEd25519PubKey = it }
                if (!attemptTransient) {
                    credentialStore.save(receiverCredentialKey(), creds)
                }

                sharedSecret = srp.sharedKeyBytes
                if (sharedSecret != null) {
                    didTransientPairing = attemptTransient
                    Log.d(TAG, "Pair-setup complete (transient=$attemptTransient), shared secret length=${sharedSecret!!.size}")
                    return true
                }
            } else if (resp1.code == 470) {
                Log.d(TAG, "${if (attemptTransient) "Transient" else "Full"} pairing rejected (470), ${if (attemptTransient) "retrying with full" else "giving up"}")
                continue
            } else {
                Log.e(TAG, "pair-setup M1 failed: ${resp1.code}")
                return false
            }
        }
        Log.e(TAG, "Pair-setup: all attempts failed")
        return false
    }

    private fun doPairVerify(): Boolean {
        Log.d(TAG, "Pair-verify: starting")

        val keyPair = AirPlay2Crypto.generateCurve25519KeyPair()
        ecdhKeyPair = keyPair
        val clientPub = AirPlay2Crypto.getPublicKeyBytes(keyPair)

        val m1 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(1),
            TlvUtil.TLV_PUBLIC_KEY to clientPub
        )
        val resp1 = sendHttpRequest("POST", "/pair-verify", "application/pairing+tlv8", m1)
        Log.d(TAG, "pair-verify M1 response: ${resp1.code}")
        if (resp1.code != 200) { Log.e(TAG, "pair-verify M1 failed: ${resp1.code}"); return false }

        val respBody = resp1.body
        if (respBody == null) { Log.e(TAG, "pair-verify M1 no body"); return false }
        val parsed1 = TlvUtil.parse(respBody)
        val stateByte = parsed1[TlvUtil.TLV_STATE]?.firstOrNull()?.get(0)
        if (stateByte == null) { Log.e(TAG, "pair-verify M1 no state TLV"); return false }
        if (stateByte != 2.toByte()) { Log.e(TAG, "pair-verify: unexpected state ${stateByte.toInt()}"); return false }

        val serverPubKey = parsed1[TlvUtil.TLV_PUBLIC_KEY]?.firstOrNull()
        if (serverPubKey == null) { Log.e(TAG, "pair-verify M1 no public key TLV"); return false }
        val encryptedData = parsed1[TlvUtil.TLV_ENCRYPTED_DATA]?.firstOrNull()
        if (encryptedData == null) { Log.e(TAG, "pair-verify M1 no encrypted data TLV"); return false }

        // ECDH shared = curve25519(client_eph_priv, server_eph_pub)
        val shared = AirPlay2Crypto.curve25519Agree(
            keyPair.private as X25519PrivateKeyParameters, serverPubKey
        )

        // HKDF decrypt key: HKDF(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", 32)
        val sessionKey = AirPlay2Crypto.hkdfSha512(
            "Pair-Verify-Encrypt-Salt".toByteArray(Charsets.UTF_8),
            shared,
            "Pair-Verify-Encrypt-Info".toByteArray(Charsets.UTF_8),
            32
        )

        // Fixed nonce: 00 00 00 00 "PV-Msg02"
        val nonce = ByteArray(12)
        System.arraycopy("PV-Msg02".toByteArray(Charsets.UTF_8), 0, nonce, 4, 8)

        // Server response: ciphertext || tag (no nonce)
        if (encryptedData.size < 16) { Log.e(TAG, "pair-verify M2 encrypted data too short"); return false }
        val ciphertext = encryptedData.copyOfRange(0, encryptedData.size - 16)
        val tag = encryptedData.copyOfRange(encryptedData.size - 16, encryptedData.size)

        val decrypted = try {
            AirPlay2Crypto.chacha20Poly1305Decrypt(sessionKey, nonce, ciphertext, ByteArray(0), tag)
        } catch (e: Exception) {
            Log.e(TAG, "pair-verify M2 decrypt failed", e); return false
        }
        Log.d(TAG, "M2 decrypted size=${decrypted.size}")

        // Parse decrypted inner TLV: {Identifier, Signature}
        val innerParsed = TlvUtil.parse(decrypted)
        val serverId = innerParsed[TlvUtil.TLV_IDENTIFIER]?.firstOrNull()
        val serverSignature = innerParsed[TlvUtil.TLV_SIGNATURE]?.firstOrNull()
        if (serverId == null || serverSignature == null) {
            Log.e(TAG, "pair-verify M2 missing identifier or signature in decrypted data")
            return false
        }
        Log.d(TAG, "M2 serverId=${serverId.toString(Charsets.UTF_8)} sigSize=${serverSignature.size}")

        // Authenticate the receiver: signature over
        // (server_eph_pub || server_id || client_eph_pub) against the long-term
        // key from /info or the mDNS TXT record. A mismatch means we are not
        // talking to the device we paired with, so it must be fatal.
        val verifyInfo = serverPubKey + serverId + clientPub
        val serverEd25519Pub = this.deviceEd25519PubKey
        if (serverEd25519Pub != null && serverEd25519Pub.size == 32) {
            if (!AirPlay2Crypto.ed25519Verify(serverEd25519Pub, verifyInfo, serverSignature)) {
                Log.e(TAG, "pair-verify M2 server signature rejected")
                return false
            }
        } else {
            Log.w(TAG, "No server Ed25519 public key, cannot authenticate receiver")
        }

        // Build M3: TLV{Identifier, Signature: ed25519(client_eph_pub || identifier || server_eph_pub)}
        // signed with the long-term key pair-setup M5 registered.
        val creds = pairingCredentials()
        val identifier = creds.pairingId.toByteArray(Charsets.UTF_8)
        val m3SignData = clientPub + identifier + serverPubKey
        val m3Sig = AirPlay2Crypto.ed25519SignWithSeed(creds.ed25519Seed, m3SignData)

        val m3DeviceInfoTlv = TlvUtil.build(
            TlvUtil.TLV_IDENTIFIER to identifier,
            TlvUtil.TLV_SIGNATURE to m3Sig
        )

        // Encrypt with fixed nonce: 00 00 00 00 "PV-Msg03"
        val m3Nonce = ByteArray(12)
        System.arraycopy("PV-Msg03".toByteArray(Charsets.UTF_8), 0, m3Nonce, 4, 8)
        val (m3Ciphertext, m3Tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
            sessionKey, m3Nonce, m3DeviceInfoTlv, ByteArray(0)
        )
        val m3Encrypted = m3Ciphertext + m3Tag

        val m3 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(3),
            TlvUtil.TLV_ENCRYPTED_DATA to m3Encrypted
        )
        Log.d(TAG, "pair-verify M3 request size=${m3.size}")
        val resp3 = sendHttpRequest("POST", "/pair-verify", "application/pairing+tlv8", m3)
        Log.d(TAG, "pair-verify M3 response: ${resp3.code}")
        if (resp3.code != 200) { Log.e(TAG, "pair-verify M3 failed: ${resp3.code}"); return false }

        // From here the whole RTSP channel is HAP framed. The key labels are
        // HKDF(shared, "Control-Salt", "Control-{Write,Read}-Encryption-Key");
        // the previous "Pair-Verify-AES-Key" salt belongs to the unrelated raw
        // (non-HAP) pair-verify variant.
        val controlSalt = "Control-Salt".toByteArray(Charsets.UTF_8)
        hapWriteKey = AirPlay2Crypto.hkdfSha512(
            controlSalt, shared, "Control-Write-Encryption-Key".toByteArray(Charsets.UTF_8), 32
        )
        hapReadKey = AirPlay2Crypto.hkdfSha512(
            controlSalt, shared, "Control-Read-Encryption-Key".toByteArray(Charsets.UTF_8), 32
        )
        hapWriteNonce = 0
        hapReadNonce = 0
        channelEncrypted = true
        cipherKeys = AirPlay2Crypto.PairKeysResult(hapWriteKey!!, hapReadKey!!, 0L, 0L)
        supportsEncryption = true
        Log.d(TAG, "Pair-verify complete, control channel encrypted")
        return true
    }

    /**
     * FairPlay SAP. Receivers advertising feature bit 14 reject SETUP with
     * RTSP 455 until /fp-setup completes. It proves the sender runs genuine
     * client code; on an encrypted session the audio key is still our own shk.
     */
    private fun doFairPlaySetup(): Boolean {
        return try {
            val session = FairPlaySapSession(secureRandom)
            val header = listOf("X-Apple-ET" to "32")
            val r1 = sendRequest("POST /fp-setup RTSP/1.0", "application/octet-stream", session.message1(), header)
            if (r1.code == 404) {
                Log.w(TAG, "/fp-setup missing despite the FPSAP feature bit; continuing")
                return true
            }
            if (r1.code != 200) { Log.e(TAG, "fp-setup m1 failed: ${r1.code}"); return false }
            val m3 = session.exchangeM3(r1.body ?: return false)
            val r2 = sendRequest("POST /fp-setup RTSP/1.0", "application/octet-stream", m3, header)
            if (r2.code != 200) { Log.e(TAG, "fp-setup m3 failed: ${r2.code}"); return false }
            session.finish(r2.body ?: return false)
            Log.d(TAG, "FairPlay SAP handshake complete")
            true
        } catch (e: Exception) {
            Log.e(TAG, "FairPlay SAP failed", e)
            false
        }
    }

    private fun sendSetupSession(): Boolean {
        val localAddress = socket.localAddress?.hostAddress ?: "0.0.0.0"
        val plist = BinaryPlist.makeSessionPlist(
            sessionUuid = sessionUuid,
            deviceId = deviceId,
            name = clientName,
            model = "Linux",
            sourceVersion = if (usePtp) SOURCE_VERSION_PTP else SOURCE_VERSION_NTP,
            timingProtocol = if (usePtp) "PTP" else "NTP",
            timingPort = timingSocket.localPort,
            timingPeerId = deviceId,
            timingPeerAddress = localAddress
        )
        val resp = sendRtspRequest("SETUP", sessionUrl, "application/x-apple-binary-plist", plist)
        if (resp.code != 200) { Log.e(TAG, "SETUP session failed: ${resp.code}"); return false }
        sessionId = resp.headers["Session"]?.substringBefore(";")?.trim()
            ?: sessionUuid.toString().uppercase()
        parseTransportResponse(resp.headers["Transport"] ?: "")
        resp.body?.let { parseSessionResponse(it) }
        if (usePtp && ptpTimelineId == 0L) {
            Log.e(TAG, "PTP SETUP response omitted timingPeerInfo.ClockID")
            return false
        }
        return true
    }

    private fun sendRecord(): Boolean {
        val resp = sendRtspRequest(
            "RECORD", sessionUrl, null, null,
            extraHeaders = listOf("Range" to "npt=0-", "RTP-Info" to "seq=0;rtptime=0")
        )
        Log.d(TAG, "RECORD response: code=${resp.code}")
        return resp.code == 200 || resp.code == 201
    }

    private fun sendSetupStream(): Boolean {
        // The audio key is a fresh random 32-byte value published as `shk`; it
        // is not derived from the pairing secret. Unencrypted sessions publish
        // no key and stream in the clear.
        val shk = if (channelEncrypted) ByteArray(32).also(secureRandom::nextBytes) else null
        useStreamConnections = hasFeature(FEATURE_STREAM_CONNECTIONS)

        val plist = BinaryPlist.makeStreamPlist(
            controlPort = controlSocket.localPort,
            shk = shk,
            streamConnectionId = streamConnectionId,
            sampleRate = sampleRate,
            spf = frameSize,
            latencyMin = 0L,
            latencyMax = LATENCY_SAMPLES.toLong(),
            useStreamConnections = useStreamConnections
        )
        val resp = sendRtspRequest("SETUP", sessionUrl, "application/x-apple-binary-plist", plist)
        if (resp.code != 200) { Log.e(TAG, "SETUP stream failed: ${resp.code}"); return false }

        resp.body?.let { body ->
            try {
                val dict = BinaryPlist.decode(body)
                val streams = dict["streams"]
                if (streams is List<*> && streams.isNotEmpty()) {
                    (streams[0] as? Map<*, *>)?.let { stream ->
                        (stream["dataPort"] as? Long)?.toInt()?.let { if (it > 0) audioRemotePort = it }
                        (stream["controlPort"] as? Long)?.toInt()?.let { if (it > 0) controlRemotePort = it }
                        // The streamConnections layout answers with both ports
                        // nested instead of dataPort/controlPort.
                        val connections = stream["streamConnections"] as? Map<*, *>
                        connectionPort(connections, "streamConnectionTypeRTP")?.let { audioRemotePort = it }
                        connectionPort(connections, "streamConnectionTypeRTCP")?.let { controlRemotePort = it }
                    }
                }
                (dict["eventPort"] as? Long)?.toInt()?.let { if (it > 0) eventPort = it }
            } catch (e: Exception) {
                Log.w(TAG, "SETUP stream plist parse failed: ${e.message}")
            }
        }

        if (audioRemotePort <= 0) {
            Log.e(TAG, "SETUP stream returned no data port")
            return false
        }

        audioSocket = DatagramSocket(0)
        // Audio is encrypted with the key we published, not with the control
        // channel keys.
        audioKey = shk
        audioNonceCounter = 0
        Log.d(TAG, "Audio stream: data=$audioRemotePort ctrl=$controlRemotePort encrypted=${shk != null}")
        return true
    }

    private fun connectionPort(connections: Map<*, *>?, key: String): Int? {
        val port = (connections?.get(key) as? Map<*, *>)?.get("streamConnectionKeyPort") as? Long
        return port?.toInt()?.takeIf { it > 0 }
    }

    private fun parseSessionResponse(body: ByteArray) {
        try {
            val dict = BinaryPlist.decode(body)
            (dict["eventPort"] as? Long)?.toInt()?.let { if (it > 0) eventPort = it }
            // PTP sync packets must carry the receiver's clock identity.
            ((dict["timingPeerInfo"] as? Map<*, *>)?.get("ClockID") as? Long)
                ?.let { if (it != 0L) ptpTimelineId = it }
        } catch (e: Exception) {
            Log.w(TAG, "parseSessionResponse failed: ${e.message}")
        }
    }

    private fun parseTransportResponse(transport: String) {
        transport.split(";").forEach { part ->
            when {
                part.trim().startsWith("server_port=") ->
                    audioRemotePort = part.substringAfter("=").toIntOrNull() ?: 0
                part.trim().startsWith("control_port=") ->
                    controlRemotePort = part.substringAfter("=").toIntOrNull() ?: 0
                part.trim().startsWith("timing_port=") ->
                    timingRemotePort = part.substringAfter("=").toIntOrNull() ?: 0
            }
        }
        Log.d(TAG, "Transport: audio=$audioRemotePort ctrl=$controlRemotePort timing=$timingRemotePort")
    }

    private fun alacEncodeUncompressed(pcm: ByteArray): ByteArray {
        // Replicates alac_encode_uncompressed from alac_wrapper.cpp in pure Kotlin.
        // Writes a 23-bit ALAC uncompressed frame header, then byte-swaps each
        // little-endian stereo sample to big-endian, all packed into a bit stream.
        val out = ByteArray(3 + pcm.size + 1)
        var p = 0
        var bpos = 0

        fun writeBits(v: Int, blen: Int) {
            val lb = 8 - bpos
            val rb = lb - blen
            if (rb >= 0) {
                val bd = (v shl rb) and 0xFF
                out[p] = if (bpos == 0) bd.toByte() else (out[p].toInt() or bd).toByte()
                if (rb == 0) { p++; bpos = 0 } else bpos += blen
            } else {
                out[p] = (out[p].toInt() or ((v ushr (-rb)) and 0xFF)).toByte()
                p++
                out[p] = ((v shl (8 + rb)) and 0xFF).toByte()
                bpos = -rb
            }
        }

        writeBits(1, 3); writeBits(0, 4); writeBits(0, 8)
        writeBits(0, 4); writeBits(0, 1); writeBits(0, 2); writeBits(1, 1)

        var i = 0
        while (i < pcm.size) {
            writeBits(pcm[i + 1].toInt() and 0xFF, 8)  // L high byte
            writeBits(pcm[i + 0].toInt() and 0xFF, 8)  // L low byte
            writeBits(pcm[i + 3].toInt() and 0xFF, 8)  // R high byte
            writeBits(pcm[i + 2].toInt() and 0xFF, 8)  // R low byte
            i += 4
        }
        writeBits(7, 3)
        return out
    }

    fun sendAudioFrame(pcm: ByteArray): Boolean {
        try {
            val payload = alacEncodeUncompressed(pcm)
            val packet = buildRtpPacket(payload)
            val socket = audioSocket ?: return false

            val key = audioKey
            val datagram = if (key != null) {
                val counter = synchronized(this) { audioNonceCounter++ }
                val nonce = ByteArray(12)
                for (i in 0..7) nonce[4 + i] = ((counter shr (i * 8)) and 0xFF).toByte()
                val aad = packet.copyOfRange(4, 12)
                val (encrypted, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
                    key, nonce, packet.copyOfRange(12, packet.size), aad
                )
                packet.copyOfRange(0, 12) + encrypted + tag + nonce.copyOfRange(4, 12)
            } else {
                packet
            }
            socket.send(DatagramPacket(datagram, datagram.size, InetAddress.getByName(host), audioRemotePort))

            sequence = (sequence + 1) and 0xFFFF
            rtpTimestamp += frameSize
            return true
        } catch (e: Exception) {
            Log.e(TAG, "sendAudioFrame failed", e)
            return false
        }
    }

    private fun buildRtpPacket(payload: ByteArray): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = 0x60.toByte()
        header[2] = ((sequence shr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        val ts = rtpTimestamp
        header[4] = ((ts shr 24) and 0xFF).toByte()
        header[5] = ((ts shr 16) and 0xFF).toByte()
        header[6] = ((ts shr 8) and 0xFF).toByte()
        header[7] = (ts and 0xFF).toByte()
        val ssrcInt = ssrc.toInt()
        header[8] = ((ssrcInt shr 24) and 0xFF).toByte()
        header[9] = ((ssrcInt shr 16) and 0xFF).toByte()
        header[10] = ((ssrcInt shr 8) and 0xFF).toByte()
        header[11] = (ssrcInt and 0xFF).toByte()
        return header + payload
    }

    /**
     * TimeAnnounce on the control port: 20 bytes / payload type 0xd4 for NTP,
     * 28 bytes / 0xd7 plus the receiver's timeline id for PTP.
     */
    private fun sendSyncPacket() {
        if (controlRemotePort <= 0) return
        val networkTime = currentNtpTime()
        val packet = ByteArray(if (usePtp) 28 else 20)
        packet[0] = (if (syncPacketSent) 0x80 else 0x90).toByte()
        packet[1] = (if (usePtp) 0xD7 else 0xD4).toByte()
        packet[2] = 0
        packet[3] = 4
        syncPacketSent = true
        writeUInt32(packet, 4, rtpTimestamp - LATENCY_SAMPLES)
        if (usePtp) {
            writeUInt64(packet, 8, ptpNanoseconds(networkTime))
            writeUInt32(packet, 16, rtpTimestamp)
            writeUInt64(packet, 20, ptpTimelineId)
        } else {
            writeUInt64(packet, 8, networkTime)
            writeUInt32(packet, 16, rtpTimestamp)
        }
        try {
            controlSocket.send(
                DatagramPacket(packet, packet.size, InetAddress.getByName(host), controlRemotePort)
            )
        } catch (_: Exception) {}
    }

    /** seconds.32 fixed point -> nanoseconds. */
    private fun ptpNanoseconds(timestamp: Long): Long {
        val seconds = timestamp ushr 32
        val fraction = timestamp and 0xFFFFFFFFL
        return seconds * 1_000_000_000L + (fraction * 1_000_000_000L ushr 32)
    }

    /**
     * Answer the receiver's NTP timing requests (0xd2) with 0xd3 responses.
     * Without this an NTP receiver never establishes a media clock and stays
     * silent.
     */
    private fun startTimingResponder() {
        timingThread = thread(name = "ap2-timing", isDaemon = true) {
            val buf = ByteArray(64)
            while (running) {
                try {
                    val request = DatagramPacket(buf, buf.size)
                    timingSocket.receive(request)
                    if (request.length < 32 || (buf[1].toInt() and 0xFF) != 0xD2) continue
                    val receiveTime = currentNtpTime()
                    val response = ByteArray(32)
                    response[0] = 0x80.toByte()
                    response[1] = 0xD3.toByte()
                    response[2] = buf[2]
                    response[3] = buf[3]
                    System.arraycopy(buf, 24, response, 8, 8)
                    writeUInt64(response, 16, receiveTime)
                    writeUInt64(response, 24, currentNtpTime())
                    timingSocket.send(DatagramPacket(response, response.size, request.address, request.port))
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "timing responder: ${e.message}")
                }
            }
        }
    }

    fun setVolume(db: Double) {
        val body = "volume: ${"%.6f".format(java.util.Locale.US, db)}\r\n"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        sendRtspRequest("SET_PARAMETER", sessionUrl, "text/parameters", bodyBytes)
    }

    fun sendMetadata(title: String?, artist: String?, album: String?, artworkBytes: ByteArray? = null) {
        val dict = linkedMapOf<String, Any?>()
        if (!title.isNullOrEmpty()) dict["dmap.itemname"] = title
        if (!artist.isNullOrEmpty()) dict["daap.songartist"] = artist
        if (!album.isNullOrEmpty()) dict["daap.songalbum"] = album
        if (dict.isNotEmpty()) {
            sendRtspRequest("SET_PARAMETER", sessionUrl,
                "application/x-apple-binary-plist", BinaryPlist.encode(dict))
        }
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            sendRtspRequest("SET_PARAMETER", sessionUrl, "image/jpeg", artworkBytes)
        }
    }

    fun sendProgress(positionMs: Long, durationMs: Long) {
        val start = 0L
        val current = (positionMs * sampleRate / 1000L) + rtpTimestamp
        val end = (durationMs * sampleRate / 1000L)
        val body = "progress: $start/$current/$end\r\n".toByteArray(Charsets.UTF_8)
        sendRtspRequest("SET_PARAMETER", sessionUrl, "text/parameters", body)
    }

    private fun connectEventPort() {
        if (eventPort <= 0) return
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, eventPort), 5000)
            eventSocket = s
            startEventReadLoop(s)
            Log.d(TAG, "Event port $eventPort connected")
        } catch (e: Exception) {
            Log.w(TAG, "Event port connect failed: ${e.message}")
        }
    }

    private fun startEventReadLoop(s: Socket) {
        thread(name = "ap2-event", isDaemon = true) {
            try {
                val input = s.getInputStream()
                while (running) {
                    val msg = readEventMessage(input) ?: break
                    dispatchEventMessage(msg)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Event loop ended: ${e.message}")
            }
        }
    }

    private data class EventMessage(val method: String, val headers: Map<String, String>, val body: ByteArray?)

    private fun readEventMessage(input: InputStream): EventMessage? {
        val headerLines = mutableListOf<String>()
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            if (b == '\n'.code) {
                val line = buf.toString().trimEnd('\r', '\n')
                buf.clear()
                if (line.isEmpty()) break
                headerLines.add(line)
            }
        }
        if (headerLines.isEmpty()) return null
        val method = headerLines[0].substringBefore(' ')
        val headers = linkedMapOf<String, String>()
        var contentLength = 0
        for (i in 1 until headerLines.size) {
            val parts = headerLines[i].split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                headers[key] = value
                if (key.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }
        var body: ByteArray? = null
        if (contentLength > 0) {
            body = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(body, offset, contentLength - offset)
                if (read < 0) break
                offset += read
            }
        }
        return EventMessage(method, headers, body)
    }

    private fun dispatchEventMessage(msg: EventMessage) {
        Log.d(TAG, "Event: ${msg.method} ct=${msg.headers["Content-Type"]}")
        val body = msg.body ?: return
        if (msg.headers["Content-Type"]?.contains("apple-binary-plist") != true) return
        try {
            val dict = BinaryPlist.decode(body)
            when (dict["type"]) {
                "volume" -> {
                    val db = when (val v = dict["value"]) {
                        is Double -> v
                        is Long -> v.toDouble()
                        else -> return
                    }
                    Log.d(TAG, "Event volume: $db dB")
                    eventListener?.onVolumeChange(db)
                }
                "command" -> {
                    val name = dict["name"] as? String ?: return
                    Log.d(TAG, "Event command: $name")
                    eventListener?.onRemoteCommand(name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Event dispatch failed: ${e.message}")
        }
    }

    /** Best-effort graceful session end. Call before [close] where possible so the
     *  receiver drops the session immediately instead of waiting out its own timeout. */
    fun teardown() {
        try {
            if (sessionUrl.isNotEmpty()) sendRtspRequest("TEARDOWN", sessionUrl, null, null)
        } catch (_: Exception) {}
    }

    fun close() {
        running = false
        syncThread?.interrupt()
        keepAliveThread?.interrupt()
        timingThread?.interrupt()
        audioSocket?.close()
        try { eventSocket?.close() } catch (_: Exception) {}
        controlSocket.close()
        timingSocket.close()
        try { socket.close() } catch (_: Exception) {}
    }

    private fun sendHttpRequest(method: String, path: String, contentType: String?, body: ByteArray?): HttpResponse {
        return sendRequest("$method $path RTSP/1.0", contentType, body)
    }

    private fun sendPairingRequest(method: String, path: String, contentType: String?, body: ByteArray?, hkpType: Int): HttpResponse {
        return sendRequest("$method $path RTSP/1.0", contentType, body, listOf("X-Apple-HKP" to hkpType.toString()))
    }

    private fun sendRtspRequest(
        method: String,
        url: String,
        contentType: String?,
        body: ByteArray?,
        extraHeaders: List<Pair<String, String>> = emptyList()
    ): HttpResponse {
        return sendRequest("$method $url RTSP/1.0", contentType, body, extraHeaders)
    }

    private fun sendRequest(requestLine: String, contentType: String?, body: ByteArray?, extraHeaders: List<Pair<String, String>> = emptyList()): HttpResponse {
        synchronized(this) {
            val out = output ?: return HttpResponse(0, emptyMap(), null)
            val req = buildRequest(requestLine, contentType, body, extraHeaders)
            // Headers and body are one plaintext blob for framing purposes.
            val frame = req.toByteArray(Charsets.UTF_8) + (body ?: ByteArray(0))
            writeFramed(out, frame)
            out.flush()
            return readResponse()
        }
    }

    /**
     * HAP framing: split the plaintext into 1024-byte chunks and send each as
     * `[length LE16][ciphertext][16-byte tag]`, with the length prefix as AAD
     * and an incrementing little-endian counter nonce.
     */
    private fun writeFramed(out: OutputStream, data: ByteArray) {
        if (!channelEncrypted) { out.write(data); return }
        val key = hapWriteKey ?: return
        var offset = 0
        while (offset < data.size) {
            val n = minOf(HAP_FRAME_SIZE, data.size - offset)
            val lengthPrefix = byteArrayOf((n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte())
            val nonce = ByteArray(12)
            for (i in 0..7) nonce[4 + i] = ((hapWriteNonce shr (i * 8)) and 0xFF).toByte()
            val (ct, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
                key, nonce, data.copyOfRange(offset, offset + n), lengthPrefix
            )
            out.write(lengthPrefix)
            out.write(ct)
            out.write(tag)
            hapWriteNonce++
            offset += n
        }
    }

    /** Transparently decrypts HAP frames once the channel is encrypted. */
    private inner class HapInputStream(private val raw: InputStream) : InputStream() {
        private var plain: ByteArray = ByteArray(0)
        private var pos = 0

        override fun read(): Int {
            if (!channelEncrypted) return raw.read()
            if (pos >= plain.size && !fill()) return -1
            return plain[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!channelEncrypted) return raw.read(b, off, len)
            if (len == 0) return 0
            if (pos >= plain.size && !fill()) return -1
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        private fun readFully(buf: ByteArray): Boolean {
            var offset = 0
            while (offset < buf.size) {
                val n = raw.read(buf, offset, buf.size - offset)
                if (n < 0) return false
                offset += n
            }
            return true
        }

        private fun fill(): Boolean {
            val key = hapReadKey ?: return false
            val lengthPrefix = ByteArray(2)
            if (!readFully(lengthPrefix)) return false
            val length = (lengthPrefix[0].toInt() and 0xFF) or ((lengthPrefix[1].toInt() and 0xFF) shl 8)
            val sealed = ByteArray(length + 16)
            if (!readFully(sealed)) return false
            val nonce = ByteArray(12)
            for (i in 0..7) nonce[4 + i] = ((hapReadNonce shr (i * 8)) and 0xFF).toByte()
            plain = AirPlay2Crypto.chacha20Poly1305Decrypt(
                key, nonce,
                sealed.copyOfRange(0, length),
                lengthPrefix,
                sealed.copyOfRange(length, sealed.size)
            )
            pos = 0
            hapReadNonce++
            return plain.isNotEmpty()
        }
    }

    private fun buildRequest(requestLine: String, contentType: String?, body: ByteArray?, extraHeaders: List<Pair<String, String>> = emptyList()): String {
        val sb = StringBuilder()
        sb.append("$requestLine\r\n")
        sb.append("CSeq: ${++cseq}\r\n")
        sb.append("User-Agent: $USER_AGENT\r\n")
        sb.append("Client-Instance: ${deviceId.replace(":", "")}\r\n")
        sb.append("DACP-ID: $dacpId\r\n")
        sb.append("Active-Remote: $activeRemote\r\n")
        if (sessionId != null) sb.append("Session: $sessionId\r\n")
        for ((k, v) in extraHeaders) sb.append("$k: $v\r\n")
        if (contentType != null && body != null && body.isNotEmpty()) {
            sb.append("Content-Type: $contentType\r\n")
        }
        // Apple's senders always send Content-Length, even for empty bodies.
        sb.append("Content-Length: ${body?.size ?: 0}\r\n")
        sb.append("\r\n")
        return sb.toString()
    }

    private fun readResponse(): HttpResponse {
        val input = input ?: return HttpResponse(0, emptyMap(), null)
        val headers = linkedMapOf<String, String>()
        var statusCode = 0
        val headerLines = mutableListOf<String>()
        val buf = StringBuilder()

        while (true) {
            val b = input.read()
            if (b < 0) break
            buf.append(b.toChar())
            if (b == '\n'.code) {
                val line = buf.toString().trimEnd('\r', '\n')
                buf.clear()
                if (line.isEmpty()) break
                headerLines.add(line)
            }
        }

        if (headerLines.isEmpty()) return HttpResponse(0, emptyMap(), null)

        val statusRegex = Regex("\\w+/\\d\\.\\d (\\d+)")
        statusRegex.find(headerLines.first())?.let {
            statusCode = it.groupValues[1].toIntOrNull() ?: 0
        }

        var contentLength = 0
        for (i in 1 until headerLines.size) {
            val line = headerLines[i]
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                headers[key] = value
                if (key.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }

        var body: ByteArray? = null
        if (contentLength > 0) {
            body = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(body, offset, contentLength - offset)
                if (read < 0) break
                offset += read
            }
            if (offset != contentLength) body = null
        }

        return HttpResponse(statusCode, headers, body)
    }

    private fun currentNtpTime(): Long {
        val ms = System.currentTimeMillis()
        val seconds = ms / 1000 + 2208988800L
        val fraction = ((ms % 1000) * 0x100000000L) / 1000
        return (seconds shl 32) or (fraction and 0xFFFFFFFFL)
    }

    private fun writeUInt32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v shr 24) and 0xFF).toByte()
        buf[off + 1] = ((v shr 16) and 0xFF).toByte()
        buf[off + 2] = ((v shr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    private fun writeUInt64(buf: ByteArray, off: Int, v: Long) {
        buf[off] = ((v shr 56) and 0xFF).toByte()
        buf[off + 1] = ((v shr 48) and 0xFF).toByte()
        buf[off + 2] = ((v shr 40) and 0xFF).toByte()
        buf[off + 3] = ((v shr 32) and 0xFF).toByte()
        buf[off + 4] = ((v shr 24) and 0xFF).toByte()
        buf[off + 5] = ((v shr 16) and 0xFF).toByte()
        buf[off + 6] = ((v shr 8) and 0xFF).toByte()
        buf[off + 7] = (v and 0xFF).toByte()
    }

    private fun startSyncLoop() {
        syncThread = thread(name = "ap2-sync") {
            while (running) { sendSyncPacket(); Thread.sleep(1000) }
        }
    }

    /** POST /feedback every 2 s; the receiver's feedback timeout is far under 25 s. */
    private fun startKeepAliveLoop() {
        keepAliveThread = thread(name = "ap2-keepalive", isDaemon = true) {
            while (running) {
                try {
                    sendHttpRequest("POST", "/feedback", null, null)
                } catch (_: Exception) {}
                try { Thread.sleep(2000) } catch (e: InterruptedException) { return@thread }
            }
        }
    }


    operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(this.size + other.size)
        System.arraycopy(this, 0, result, 0, this.size)
        System.arraycopy(other, 0, result, this.size, other.size)
        return result
    }
}
