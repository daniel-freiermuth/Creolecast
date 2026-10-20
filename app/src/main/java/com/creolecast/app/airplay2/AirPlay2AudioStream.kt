package com.creolecast.app.airplay2

import android.util.Log
import com.creolecast.app.raop.AlacEncoder
import com.creolecast.app.raop.RtpRetransmitter
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/** Where the stream SETUP told us to send audio, and the key to seal it with. */
internal class AudioStreamTarget(val dataPort: Int, val controlPort: Int, val key: ByteArray?)

/**
 * The RTP side of a session: ALAC frames on the data port, sealed with the
 * stream key when there is one, and the sync packets on the control port that
 * tie RTP time to the network clock.
 *
 * The nonce counter shares [lock] with [AirPlay2ControlChannel] requests. Sent packets
 * go to [retransmitter], verbatim (sealed), for the receiver's resend requests.
 */
internal class AirPlay2AudioStream(
    private val config: AirPlay2ClientConfig,
    private val controlSocket: DatagramSocket,
    private val retransmitter: RtpRetransmitter,
    private val lock: Any,
    private val random: SecureRandom
) : Closeable {

    companion object {

        private const val TAG = "AirPlay2Client"

        /** RTP: version 2, no marker, payload type 0x60 (96, dynamic). */
        private const val RTP_VERSION_2 = 0x80
        private const val RTP_PAYLOAD_TYPE = 0x60
        private const val RTP_HEADER_SIZE = 12
        private const val RTP_SEQUENCE_OFFSET = 2
        private const val RTP_TIMESTAMP_OFFSET = 4
        private const val RTP_SSRC_OFFSET = 8
        private const val RTP_SEQUENCE_MASK = 0xFFFF

        /** Sync packets: the first one carries the extension bit (0x90). */
        private const val SYNC_FIRST = 0x90
        private const val SYNC_NTP_PAYLOAD_TYPE = 0xD4
        private const val SYNC_PTP_PAYLOAD_TYPE = 0xD7
        private const val SYNC_NTP_SIZE = 20
        private const val SYNC_PTP_SIZE = 28
        private const val SYNC_LENGTH_FIELD = 4
        private const val SYNC_LENGTH_OFFSET = 3
        private const val SYNC_ANCHOR_RTP_OFFSET = 4
        private const val SYNC_NETWORK_TIME_OFFSET = 8
        private const val SYNC_CURRENT_RTP_OFFSET = 16
        private const val SYNC_TIMELINE_OFFSET = 20
    }

    /** RTP timestamp of the next frame. */
    var rtpTimestamp = 0
        private set

    private var sequence = 0

    /** Sequence number of the next frame. */
    val nextSequence: Int get() = sequence
    private val ssrc = random.nextInt().toUInt().toLong()
    private var syncPacketSent = false
    private var socket: DatagramSocket? = null
    private var dataPort = 0
    private var controlPort = 0

    /** Audio stream key published as `shk`; null means the audio goes out in the clear. */
    private var key: ByteArray? = null
    private var nonceCounter = 0L

    /** Random starting sequence number and timestamp, as RTP recommends. */
    fun randomizeStart() {
        sequence = random.nextInt(RTP_SEQUENCE_MASK)
        rtpTimestamp = random.nextInt()
    }

    @Suppress("MissingUseCall") // Streams until close(); `use` would close it on return.
    fun open(target: AudioStreamTarget) {
        dataPort = target.dataPort
        controlPort = target.controlPort
        socket = DatagramSocket(0)
        key = target.key
        nonceCounter = 0
        Log.d(
            TAG,
            "Audio stream: data=$dataPort ctrl=$controlPort encrypted=${target.key != null}"
        )
    }

    fun sendFrame(pcm: ByteArray): Boolean = try {
        val packet = buildRtpPacket(AlacEncoder.encodeUncompressed(pcm, hasSize = true))
        val target = socket
        if (target != null) {
            val datagram = key?.let { seal(packet, it) } ?: packet
            retransmitter.record(sequence, datagram)
            target.send(
                DatagramPacket(
                    datagram,
                    datagram.size,
                    InetAddress.getByName(config.host),
                    dataPort
                )
            )
            sequence = (sequence + 1) and RTP_SEQUENCE_MASK
            rtpTimestamp += config.frameSize
        }
        target != null
    } catch (e: IOException) {
        Log.e(TAG, "sendAudioFrame failed", e)
        false
    }

    /**
     * TimeAnnounce on the control port: 20 bytes / payload type 0xd4 for NTP,
     * 28 bytes / 0xd7 plus the timeline id for PTP (audio.go sendSyncPacketAt).
     * The frame at `rtpTimestamp - latencySamples` is anchored to the network time.
     */
    fun sendSync(clock: AirPlay2Clock) {
        val networkTime = if (controlPort > 0) clock.networkTime() else null
        if (networkTime != null) {
            val packet = buildSyncPacket(clock, networkTime)
            try {
                controlSocket.send(
                    DatagramPacket(
                        packet,
                        packet.size,
                        InetAddress.getByName(config.host),
                        controlPort
                    )
                )
            } catch (_: Exception) {
                // Best effort: the next sync packet follows a second later.
            }
        }
    }

    override fun close() {
        socket?.close()
    }

    private fun buildRtpPacket(payload: ByteArray): ByteArray {
        val header = ByteArray(RTP_HEADER_SIZE)
        header[0] = RTP_VERSION_2.toByte()
        header[1] = RTP_PAYLOAD_TYPE.toByte()
        WireBytes.putUInt16(header, RTP_SEQUENCE_OFFSET, sequence)
        WireBytes.putUInt32(header, RTP_TIMESTAMP_OFFSET, rtpTimestamp.toLong())
        WireBytes.putUInt32(header, RTP_SSRC_OFFSET, ssrc)
        return header + payload
    }

    /**
     * ChaCha20-Poly1305 with an 8-byte LE counter nonce, AAD = the RTP
     * timestamp+SSRC bytes, nonce appended after the tag (audio.go
     * sendAudioPacketWithSeqAndNonce).
     */
    private fun seal(packet: ByteArray, key: ByteArray): ByteArray {
        val counter = synchronized(lock) { nonceCounter++ }
        val nonce = WireBytes.counterNonce(counter)
        val (encrypted, tag) = AirPlay2Crypto.chacha20Poly1305Encrypt(
            key,
            nonce,
            packet.copyOfRange(RTP_HEADER_SIZE, packet.size),
            packet.copyOfRange(RTP_TIMESTAMP_OFFSET, RTP_HEADER_SIZE)
        )
        return packet.copyOfRange(0, RTP_HEADER_SIZE) + encrypted + tag +
            nonce.copyOfRange(WireBytes.CHACHA_NONCE_PREFIX_SIZE, WireBytes.CHACHA_NONCE_SIZE)
    }

    private fun buildSyncPacket(clock: AirPlay2Clock, networkTime: Long): ByteArray {
        val usePtp = clock.usePtp
        val packet = ByteArray(if (usePtp) SYNC_PTP_SIZE else SYNC_NTP_SIZE)
        packet[0] = (if (syncPacketSent) RTP_VERSION_2 else SYNC_FIRST).toByte()
        packet[1] = (if (usePtp) SYNC_PTP_PAYLOAD_TYPE else SYNC_NTP_PAYLOAD_TYPE).toByte()
        packet[SYNC_LENGTH_OFFSET] = SYNC_LENGTH_FIELD.toByte()
        syncPacketSent = true
        WireBytes.putUInt32(
            packet,
            SYNC_ANCHOR_RTP_OFFSET,
            (rtpTimestamp - config.latencySamples).toLong()
        )
        if (usePtp) {
            WireBytes.putUInt64(
                packet,
                SYNC_NETWORK_TIME_OFFSET,
                MediaClock.nanoseconds(networkTime)
            )
            WireBytes.putUInt32(packet, SYNC_CURRENT_RTP_OFFSET, rtpTimestamp.toLong())
            WireBytes.putUInt64(packet, SYNC_TIMELINE_OFFSET, clock.mediaClock.timelineId)
        } else {
            WireBytes.putUInt64(packet, SYNC_NETWORK_TIME_OFFSET, networkTime)
            WireBytes.putUInt32(packet, SYNC_CURRENT_RTP_OFFSET, rtpTimestamp.toLong())
        }
        return packet
    }
}
