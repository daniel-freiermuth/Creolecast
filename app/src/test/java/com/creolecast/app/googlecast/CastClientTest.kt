package com.creolecast.app.googlecast

import com.creolecast.app.MediaCommand
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CastMessageTest {
    @Test
    fun `encoding matches the protobuf wire format`() {
        val bytes = CastMessage("s", "d", "n", "p").encode()
        // field1 varint 0 | field2 "s" | field3 "d" | field4 "n" | field5 varint 0 | field6 "p"
        val expected = byteArrayOf(
            0x08, 0x00, 0x12, 0x01, 's'.code.toByte(), 0x1A, 0x01, 'd'.code.toByte(),
            0x22, 0x01, 'n'.code.toByte(), 0x28, 0x00, 0x32, 0x01, 'p'.code.toByte()
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `decodes string messages and skips binary payloads`() {
        val text = CastMessage.decode(CastMessage("a", "b", NS_RECEIVER, "{}").encode())
        assertEquals("a", text.sourceId)
        assertEquals("b", text.destinationId)
        assertEquals(NS_RECEIVER, text.namespace)
        assertEquals("{}", text.payloadUtf8)

        // payload_type BINARY (field 5 = 1), payload_binary (field 7) = [1, 2, 3]
        val binary =
            byteArrayOf(0x08, 0x00, 0x22, 0x01, 'n'.code.toByte(), 0x28, 0x01, 0x3A, 0x03, 1, 2, 3)
        val decoded = CastMessage.decode(binary)
        assertEquals("n", decoded.namespace)
        assertEquals(null, decoded.payloadUtf8)
    }

    @Test(expected = IOException::class)
    fun `rejects a field length beyond the frame`() {
        CastMessage.decode(byteArrayOf(0x12, 0x05, 's'.code.toByte()))
    }
}

/** Drives [CastClient] against an in-process CastV2 receiver over real TLS. */
class CastClientTest {
    private lateinit var server: FakeCastReceiver

    @Before
    fun setUp() {
        server = FakeCastReceiver().also { it.start() }
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun client(listener: CastListener = object : CastListener {}) =
        CastClient("127.0.0.1", server.port, listener)

    @Test
    fun `launches the default media receiver and loads the live stream with metadata`() {
        val client = client()
        client.connect()
        client.launch()
        client.load(
            "http://192.168.1.2:8091/stream.wav",
            "audio/wav",
            CastMetadata("Song", "Band", "Record", "http://192.168.1.2:8090/a.jpg")
        )

        assertEquals("web-1", client.transportId)
        assertEquals(1, client.mediaSessionId)
        val launch = server.received.first { it.second.str("type") == "LAUNCH" }
        assertEquals(DEFAULT_MEDIA_RECEIVER, launch.second.str("appId"))
        // A virtual connection to the app's transport must exist before talking to it.
        assertTrue(
            server.received.any {
                it.first.destinationId == "web-1" && it.second.str("type") == "CONNECT"
            }
        )
        val load = server.received.first { it.second.str("type") == "LOAD" }
        assertEquals("web-1", load.first.destinationId)
        val media = load.second["media"]!!.jsonObject
        assertEquals("http://192.168.1.2:8091/stream.wav", media.str("contentId"))
        assertEquals("audio/wav", media.str("contentType"))
        assertEquals("LIVE", media.str("streamType"))
        val meta = media["metadata"]!!.jsonObject
        assertEquals(3, meta["metadataType"]!!.jsonPrimitive.int)
        assertEquals("Song", meta.str("title"))
        assertEquals("Band", meta.str("artist"))
        assertEquals("Record", meta.str("albumName"))
        assertEquals(
            "http://192.168.1.2:8090/a.jpg",
            meta["images"]!!.jsonArray[0].jsonObject.str("url")
        )

        client.updateMetadata(CastMetadata("Next Song", "Band", "Record", null))
        val item = server.waitFor("QUEUE_UPDATE")!!.second["items"]!!.jsonArray[0].jsonObject
        assertEquals(1, item["itemId"]!!.jsonPrimitive.int)
        assertEquals(
            "Next Song",
            item["media"]!!.jsonObject["metadata"]!!.jsonObject.str("title")
        )

        client.setVolume(0.25)
        val volume = server.waitFor("SET_VOLUME")!!.second["volume"]!!.jsonObject
        assertEquals(0.25, volume["level"]!!.jsonPrimitive.double, 1e-9)

        client.stop()
        assertNotNull(server.waitFor("STOP"))
        client.close()
    }

    @Test
    fun `joins an already running receiver app instead of relaunching it`() {
        server.appRunning = true
        val client = client()
        client.connect()
        client.launch()
        assertEquals("web-1", client.transportId)
        assertTrue(server.received.none { it.second.str("type") == "LAUNCH" })
        client.close()
    }

    @Test
    fun `reports device side volume, pause and app takeover`() {
        val volume = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val gone = CountDownLatch(1)
        val client = client(
            object : CastListener {
                override fun onVolume(level: Double, muted: Boolean) {
                    if (level == 0.8) volume.countDown()
                }

                override fun onRemoteCommand(command: MediaCommand) {
                    if (command == MediaCommand.PAUSE) paused.countDown()
                }

                override fun onDisconnected(reason: CastDisconnect) {
                    if (reason == CastDisconnect.AppReplaced) gone.countDown()
                }
            }
        )
        client.connect()
        client.launch()
        client.load("http://x/stream.wav", "audio/wav", null)

        server.pushReceiverStatus(appRunning = true, level = 0.8)
        assertTrue(volume.await(3, TimeUnit.SECONDS))

        server.pushMediaStatus("PAUSED")
        assertTrue("pause on the device reaches the phone", paused.await(3, TimeUnit.SECONDS))

        server.pushReceiverStatus(appRunning = false, level = 0.8)
        assertTrue("another app taking over ends the session", gone.await(3, TimeUnit.SECONDS))
        client.close()
    }

    @Test
    fun `our own load and stop are not reported as remote commands`() {
        val commands = CopyOnWriteArrayList<MediaCommand>()
        val client = client(
            object : CastListener {
                override fun onRemoteCommand(command: MediaCommand) {
                    commands.add(command)
                }
            }
        )
        client.connect()
        client.launch()
        client.load("http://x/stream.wav", "audio/wav", null)
        server.pushMediaStatus("BUFFERING")
        server.pushMediaStatus("PLAYING")
        client.stop()
        server.pushMediaStatus("IDLE", idleReason = "CANCELLED")
        server.waitFor("STOP")
        Thread.sleep(200)
        assertEquals(emptyList<MediaCommand>(), commands.toList())
        client.close()
    }

    @Test
    fun `answers the receiver's heartbeat pings`() {
        val client = client()
        client.connect()
        server.ping()
        assertNotNull(server.waitFor("PONG"))
        client.close()
    }

    @Test
    fun `a dropped connection is reported as lost`() {
        val lost = CountDownLatch(1)
        val client = client(
            object : CastListener {
                override fun onDisconnected(reason: CastDisconnect) {
                    if (reason is CastDisconnect.ConnectionLost) lost.countDown()
                }
            }
        )
        client.connect()
        client.launch()
        server.dropConnection()
        assertTrue(lost.await(3, TimeUnit.SECONDS))
        client.close()
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

/** A minimal CastV2 receiver: TLS, framing, the receiver and media namespaces. */
internal class FakeCastReceiver {
    private val ssl: SSLServerSocket
    val port: Int get() = ssl.localPort
    val received = CopyOnWriteArrayList<Pair<CastMessage, JsonObject>>()

    @Volatile var appRunning = false
    private var out: DataOutputStream? = null
    private var socket: java.net.Socket? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var sender = "sender-0"

    init {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val name = X500Name("CN=fake-cast")
        val now = System.currentTimeMillis()
        val cert = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                name,
                BigInteger.ONE,
                Date(now - 60_000),
                Date(now + 86_400_000),
                name,
                keyPair.public
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        )
        val password = "pw".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("k", keyPair.private, password, arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        ssl = context.serverSocketFactory.createServerSocket(
            0,
            5,
            InetAddress.getLoopbackAddress()
        ) as SSLServerSocket
    }

    @Suppress("MissingUseCall") // The streams live until stop() closes the socket.
    fun start() {
        thread(isDaemon = true) {
            try {
                val s = ssl.accept()
                socket = s
                val input = DataInputStream(s.getInputStream())
                out = DataOutputStream(s.getOutputStream())
                while (true) {
                    val frame = ByteArray(input.readInt()).also { input.readFully(it) }
                    val msg = CastMessage.decode(frame)
                    val obj = json.parseToJsonElement(msg.payloadUtf8 ?: "{}").jsonObject
                    received.add(msg to obj)
                    sender = msg.sourceId
                    handle(msg, obj)
                }
            } catch (_: IOException) {
                // Client went away or the test stopped the server.
            }
        }
    }

    fun stop() {
        ssl.close()
        socket?.close()
    }

    fun dropConnection() {
        socket?.close()
    }

    fun waitFor(type: String, timeoutMs: Long = 3000): Pair<CastMessage, JsonObject>? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            received.lastOrNull { it.second.str("type") == type }?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    fun pushReceiverStatus(appRunning: Boolean, level: Double) {
        this.appRunning = appRunning
        send(RECEIVER_ID, NS_RECEIVER, receiverStatus(0, level))
    }

    fun pushMediaStatus(state: String, idleReason: String? = null) =
        send("web-1", NS_MEDIA, mediaStatus(0, state, idleReason))

    fun ping() = send(RECEIVER_ID, NS_HEARTBEAT, typed("PING"))

    private fun send(from: String, namespace: String, payload: JsonObject) {
        val bytes = CastMessage(from, sender, namespace, payload.toString()).encode()
        synchronized(this) {
            out?.writeInt(bytes.size)
            out?.write(bytes)
            out?.flush()
        }
    }

    private fun receiverStatus(requestId: Int, level: Double = 0.4) = buildJsonObject {
        put("type", "RECEIVER_STATUS")
        put("requestId", requestId)
        put(
            "status",
            buildJsonObject {
                put(
                    "applications",
                    buildJsonArray {
                        if (appRunning) {
                            add(
                                buildJsonObject {
                                    put("appId", DEFAULT_MEDIA_RECEIVER)
                                    put("sessionId", "sess-1")
                                    put("transportId", "web-1")
                                }
                            )
                        }
                    }
                )
                put(
                    "volume",
                    buildJsonObject {
                        put("level", level)
                        put("muted", false)
                    }
                )
            }
        )
    }

    private fun mediaStatus(requestId: Int, state: String, idleReason: String? = null) =
        buildJsonObject {
            put("type", "MEDIA_STATUS")
            put("requestId", requestId)
            put(
                "status",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("mediaSessionId", 1)
                            put("playerState", state)
                            put("currentItemId", 1)
                            idleReason?.let { put("idleReason", it) }
                        }
                    )
                }
            )
        }

    private fun handle(msg: CastMessage, obj: JsonObject) {
        val id = obj["requestId"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        when (msg.namespace) {
            NS_HEARTBEAT -> if (obj.str("type") == "PING") {
                send(msg.destinationId, NS_HEARTBEAT, typed("PONG"))
            }

            NS_RECEIVER -> when (obj.str("type")) {
                "LAUNCH" -> {
                    appRunning = true
                    send(RECEIVER_ID, NS_RECEIVER, receiverStatus(id))
                }

                "STOP" -> {
                    appRunning = false
                    send(RECEIVER_ID, NS_RECEIVER, receiverStatus(id))
                }

                "GET_STATUS", "SET_VOLUME" -> send(RECEIVER_ID, NS_RECEIVER, receiverStatus(id))
            }

            NS_MEDIA -> when (obj.str("type")) {
                "LOAD", "QUEUE_UPDATE" -> send("web-1", NS_MEDIA, mediaStatus(id, "PLAYING"))
            }
        }
    }
}
