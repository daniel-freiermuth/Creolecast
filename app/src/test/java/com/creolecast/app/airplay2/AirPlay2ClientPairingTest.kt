package com.creolecast.app.airplay2

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored-pairing state machine (AirPlay2Pairing.establish) against a fake
 * receiver on loopback: saved credentials -> pair-verify -> clear + reconnect
 * -> pair-setup -> PIN prompt, and which outcomes get persisted.
 */
class AirPlay2ClientPairingTest {

    private val receiver = FakeReceiver()
    private val store = RecordingCredentialStore()

    @After
    fun tearDown() {
        receiver.close()
    }

    @Test
    fun `saved pairing that verifies is reused without pair-setup or store writes`() {
        val saved = AirPlay2Credentials.generate()
        store.seed(FakeReceiver.DEVICE_ID, saved)
        receiver.trustedClients[saved.pairingId] = saved.ed25519Public

        Harness(password = null).use { harness ->
            assertTrue(harness.establish())
            assertTrue(harness.control.encrypted)
        }

        assertTrue(receiver.requestsTo(PAIR_SETUP).isEmpty())
        assertEquals(1, receiver.connectionCount)
        assertTrue(store.saves.isEmpty())
        assertTrue(store.clears.isEmpty())
        assertSame(saved, store.load(FakeReceiver.DEVICE_ID))
    }

    @Test
    fun `saved pairing the receiver rejects is cleared and re-paired on a fresh connection`() {
        store.seed(FakeReceiver.DEVICE_ID, AirPlay2Credentials.generate())
        receiver.acceptedPairSetupTypes = setOf(PairingSession.HKP_TRANSIENT)

        Harness(password = null).use { harness ->
            assertTrue(harness.establish())
            assertTrue(harness.control.encrypted)
        }

        assertEquals(listOf(FakeReceiver.DEVICE_ID), store.clears)
        assertNull(store.load(FakeReceiver.DEVICE_ID))
        assertEquals(2, receiver.connectionCount)
        assertEquals(
            listOf("POST $PAIR_VERIFY", "POST $PAIR_VERIFY"),
            receiver.requestLines(connection = 1).drop(1)
        )
        assertEquals(
            listOf("GET /info", "POST $PAIR_SETUP", "POST $PAIR_SETUP"),
            receiver.requestLines(connection = 2)
        )
    }

    @Test
    fun `transient pair-setup is never persisted`() {
        receiver.acceptedPairSetupTypes = setOf(PairingSession.HKP_TRANSIENT)

        Harness(password = null).use { harness ->
            assertTrue(harness.establish())
            assertTrue(harness.control.encrypted)
        }

        assertTrue(store.saves.isEmpty())
        assertTrue(receiver.requestsTo(PAIR_VERIFY).isEmpty())
    }

    @Test
    fun `PIN pairing is saved under the receiver DeviceID with the key pair-verify signed with`() {
        receiver.acceptedPairSetupTypes = setOf(PairingSession.HKP_SCREEN_CAPTURE)
        receiver.trustAnyClient = true

        Harness(password = PIN).use { harness ->
            assertTrue(harness.establish())
            assertTrue(harness.control.encrypted)
        }

        val (deviceId, saved) = store.saves.single()
        assertEquals(FakeReceiver.DEVICE_ID, deviceId)
        val proof = receiver.verifyProofs.single()
        assertEquals(saved.pairingId, proof.identifier)
        assertTrue(
            AirPlay2Crypto.ed25519Verify(saved.ed25519Public, proof.signedData, proof.signature)
        )
    }

    @Test
    fun `failed transient pairing without a PIN starts the PIN display and throws from connect`() {
        val client = AirPlay2Client(config(password = null))

        val thrown = assertThrows(NeedsPinException::class.java) { client.connect() }

        assertEquals(LOOPBACK, thrown.host)
        val pinStart = receiver.requestsTo("/pair-pin-start").single()
        assertEquals("POST", pinStart.method)
        assertEquals("5", pinStart.headers["X-Apple-HKP"])
        assertEquals("4", pinStart.headers["X-Apple-SupportedPINLengths"])
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun `PIN rejected for HKP 5 with an M2 error is retried as legacy HKP 3`() {
        receiver.acceptedPairSetupTypes = setOf(PairingSession.HKP_LEGACY)
        receiver.trustAnyClient = true

        Harness(password = PIN).use { harness -> assertTrue(harness.establish()) }

        val m1HkpTypes = receiver.requestsTo(PAIR_SETUP)
            .filter { it.state == 1 }
            .map { it.headers["X-Apple-HKP"] }
        assertEquals(listOf("5", "3"), m1HkpTypes)
        assertEquals(FakeReceiver.DEVICE_ID, store.saves.single().first)
    }

    @Test
    fun `pair-verify failing after PIN pair-setup makes connect return false and saves nothing`() {
        receiver.acceptedPairSetupTypes = setOf(PairingSession.HKP_SCREEN_CAPTURE)
        val client = AirPlay2Client(config(password = PIN))

        try {
            assertFalse(client.connect())
        } finally {
            client.close()
        }

        assertEquals(2, receiver.requestsTo(PAIR_VERIFY).size)
        assertTrue(store.saves.isEmpty())
        assertNull(store.load(FakeReceiver.DEVICE_ID))
    }

    private fun config(password: String?) = AirPlay2ClientConfig(
        host = LOOPBACK,
        port = receiver.port,
        deviceId = "02:00:00:00:00:01",
        dacpId = "0000000000000001",
        activeRemote = "1",
        password = password,
        credentialStore = store
    )

    /** The pairing part of AirPlay2Client.handshake, wired the same way. */
    private inner class Harness(password: String?) : Closeable {
        private val config = config(password)
        val control = AirPlay2ControlChannel(config, Any())
        private val info = AirPlay2ReceiverInfo(config.host, config.txtPk)
        private val pairing = AirPlay2Pairing(config, control, info, SecureRandom())

        fun establish(): Boolean {
            control.reopen()
            assertTrue(info.refresh(control))
            return pairing.establish {
                control.reopen()
                info.refresh(control)
            }
        }

        override fun close() = control.close()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PIN = "1234"
        const val PAIR_SETUP = "/pair-setup"
        const val PAIR_VERIFY = "/pair-verify"
    }
}

/** Counts writes so tests can tell "never saved" from "saved then cleared". */
private class RecordingCredentialStore : AirPlay2CredentialStore {
    private val backing = InMemoryCredentialStore()
    val saves = CopyOnWriteArrayList<Pair<String, AirPlay2Credentials>>()
    val clears = CopyOnWriteArrayList<String>()

    /** A pairing left over from an earlier session; not counted as a save. */
    fun seed(deviceId: String, credentials: AirPlay2Credentials) =
        backing.save(deviceId, credentials)

    override fun load(deviceId: String): AirPlay2Credentials? = backing.load(deviceId)

    override fun save(deviceId: String, credentials: AirPlay2Credentials) {
        saves += deviceId to credentials
        backing.save(deviceId, credentials)
    }

    override fun clear(deviceId: String) {
        clears += deviceId
        backing.clear(deviceId)
    }
}

/**
 * A plaintext RTSP receiver for /info, /pair-setup, /pair-verify and
 * /pair-pin-start, one connection at a time. Pair-setup answers M3 without a
 * server proof, which SRP6aClient.verifyM4 accepts (pairing.go:602-614), so no
 * SRP server is needed. Pair-verify runs the real X25519/ChaCha20 exchange and
 * checks the sender's Ed25519 signature against [trustedClients].
 */
private class FakeReceiver : Closeable {
    companion object {
        /** Deliberately not the host, so the store key must come from /info. */
        const val DEVICE_ID = "AA:BB:CC:DD:EE:FF"
        private const val SALT_SIZE = 16
        private const val VERIFY_KEY_SIZE = 32
        private const val TLV_ERROR_AUTHENTICATION: Byte = 2
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404
    }

    class Request(
        val connection: Int,
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        body: ByteArray
    ) {
        val tlv = TlvUtil.parse(body)
        val state: Int? = tlv[TlvUtil.TLV_STATE]?.firstOrNull()?.get(0)?.toInt()
    }

    /** What the sender signed in pair-verify M3, as the receiver saw it. */
    class VerifyProof(val identifier: String, val signedData: ByteArray, val signature: ByteArray)

    private class VerifySession(
        val clientPub: ByteArray,
        val serverPub: ByteArray,
        val key: ByteArray
    )

    /** HKP types whose pair-setup M1 this receiver accepts; others get an M2 error. */
    @Volatile var acceptedPairSetupTypes: Set<Int> = emptySet()

    /** Long-term Ed25519 keys by pairing id that pair-verify accepts. */
    val trustedClients = ConcurrentHashMap<String, ByteArray>()

    /** Accept any pair-verify identity (we cannot learn the key from M5 without SRP). */
    @Volatile var trustAnyClient = false

    val requests = CopyOnWriteArrayList<Request>()
    val verifyProofs = CopyOnWriteArrayList<VerifyProof>()

    @Volatile var connectionCount = 0
        private set

    private val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
    val port: Int = server.localPort

    @Volatile private var current: Socket? = null

    init {
        thread(isDaemon = true, name = "FakeAirPlayReceiver") { acceptLoop() }
    }

    fun requestsTo(path: String) = requests.filter { it.path == path }

    fun requestLines(connection: Int) =
        requests.filter { it.connection == connection }.map { "${it.method} ${it.path}" }

    override fun close() {
        server.close()
        current?.close()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            current = socket
            connectionCount++
            try {
                socket.use { serve(it, connectionCount) }
            } catch (_: IOException) {
                // The client dropped the connection; wait for the next one.
            }
        }
    }

    private fun serve(socket: Socket, connection: Int) {
        BufferedInputStream(socket.getInputStream()).use { input ->
            val output = socket.getOutputStream()
            var verify: VerifySession? = null
            while (true) {
                val head = RtspWire.readHead(input)
                if (!head.complete || head.lines.isEmpty()) return
                val (method, path) = head.lines.first().split(" ")
                val headers = RtspWire.parseHeaders(head.lines)
                val body = ByteArray(headers.contentLength)
                RtspWire.readFully(input, body)
                val request = Request(connection, method, path, headers.values, body)
                requests += request

                val (code, reply) = when (path) {
                    "/info" -> HTTP_OK to BinaryPlist.encode(mapOf("deviceID" to DEVICE_ID))

                    "/pair-setup" -> HTTP_OK to pairSetup(request)

                    "/pair-verify" -> HTTP_OK to when (request.state) {
                        1 -> verifyM2(request).also { verify = it.first }.second
                        else -> verifyM4(checkNotNull(verify), request)
                    }

                    "/pair-pin-start" -> HTTP_OK to ByteArray(0)

                    else -> HTTP_NOT_FOUND to ByteArray(0)
                }
                val cseq = headers.values["CSeq"].orEmpty()
                val status =
                    "RTSP/1.0 $code X\r\nCSeq: $cseq\r\nContent-Length: ${reply.size}\r\n\r\n"
                output.write(status.toByteArray(Charsets.UTF_8) + reply)
                output.flush()
            }
        }
    }

    private fun pairSetup(request: Request): ByteArray = when (request.state) {
        1 -> {
            val hkp = request.headers["X-Apple-HKP"]?.toIntOrNull()
            if (hkp in acceptedPairSetupTypes) {
                TlvUtil.build(
                    TlvUtil.TLV_STATE to byteArrayOf(2),
                    TlvUtil.TLV_SALT to ByteArray(SALT_SIZE),
                    // Any 0 < B < N passes the client's RFC 5054 check.
                    TlvUtil.TLV_PUBLIC_KEY to byteArrayOf(2)
                )
            } else {
                TlvUtil.build(
                    TlvUtil.TLV_STATE to byteArrayOf(2),
                    TlvUtil.TLV_ERROR to byteArrayOf(TLV_ERROR_AUTHENTICATION)
                )
            }
        }

        3 -> TlvUtil.build(TlvUtil.TLV_STATE to byteArrayOf(4))

        else -> TlvUtil.build(TlvUtil.TLV_STATE to byteArrayOf(6))
    }

    private fun verifyM2(request: Request): Pair<VerifySession, ByteArray> {
        val clientPub = checkNotNull(request.tlv[TlvUtil.TLV_PUBLIC_KEY]?.firstOrNull())
        val keyPair = AirPlay2Crypto.generateCurve25519KeyPair()
        val serverPub = AirPlay2Crypto.getPublicKeyBytes(keyPair)
        val shared = AirPlay2Crypto.curve25519Agree(
            keyPair.private as X25519PrivateKeyParameters,
            clientPub
        )
        val session = VerifySession(
            clientPub,
            serverPub,
            AirPlay2Crypto.hkdfSha512(
                "Pair-Verify-Encrypt-Salt".toByteArray(Charsets.UTF_8),
                shared,
                "Pair-Verify-Encrypt-Info".toByteArray(Charsets.UTF_8),
                VERIFY_KEY_SIZE
            )
        )
        val (ciphertext, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
            session.key,
            WireBytes.labelNonce("PV-Msg02"),
            TlvUtil.build(TlvUtil.TLV_IDENTIFIER to DEVICE_ID.toByteArray(Charsets.UTF_8)),
            ByteArray(0)
        )
        val m2 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(2),
            TlvUtil.TLV_PUBLIC_KEY to serverPub,
            TlvUtil.TLV_ENCRYPTED_DATA to (ciphertext + tag)
        )
        return session to m2
    }

    private fun verifyM4(session: VerifySession, request: Request): ByteArray {
        val sealed = checkNotNull(request.tlv[TlvUtil.TLV_ENCRYPTED_DATA]?.firstOrNull())
        val tagStart = sealed.size - WireBytes.AEAD_TAG_SIZE
        val inner = TlvUtil.parse(
            AirPlay2Crypto.chacha20Poly1305Decrypt(
                session.key,
                WireBytes.labelNonce("PV-Msg03"),
                sealed.copyOfRange(0, tagStart),
                ByteArray(0),
                sealed.copyOfRange(tagStart, sealed.size)
            )
        )
        val identifier = checkNotNull(inner[TlvUtil.TLV_IDENTIFIER]?.firstOrNull())
        val signature = checkNotNull(inner[TlvUtil.TLV_SIGNATURE]?.firstOrNull())
        val proof = VerifyProof(
            identifier.toString(Charsets.UTF_8),
            session.clientPub + identifier + session.serverPub,
            signature
        )
        verifyProofs += proof
        val trusted = trustAnyClient || trustedClients[proof.identifier]?.let {
            AirPlay2Crypto.ed25519Verify(it, proof.signedData, proof.signature)
        } == true
        return if (trusted) {
            TlvUtil.build(TlvUtil.TLV_STATE to byteArrayOf(4))
        } else {
            TlvUtil.build(
                TlvUtil.TLV_STATE to byteArrayOf(4),
                TlvUtil.TLV_ERROR to byteArrayOf(TLV_ERROR_AUTHENTICATION)
            )
        }
    }
}
