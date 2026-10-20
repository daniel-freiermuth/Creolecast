package com.creolecast.app.raop

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/** Packets kept for re-sending: 1024 × 352 samples ≈ 8 s at 44.1 kHz. */
private const val HISTORY = 1024
private const val RECEIVE_TIMEOUT_MS = 500
private const val MAX_CONTROL_PACKET = 1500

/** Resend request (RTP payload type 0x55, marker bit set on the wire as 0xD5). */
private const val PT_RESEND_REQUEST = 0x55
private const val PAYLOAD_TYPE_MASK = 0x7F
private const val RESEND_REQUEST_BYTES = 8
private const val RESEND_FIRST_SEQ_OFFSET = 4
private const val RESEND_COUNT_OFFSET = 6
private const val SEQUENCE_MASK = 0xFFFF

/** Retransmitted packets: `80 D6 00 01`, then the original datagram. */
private val RESEND_HEADER = byteArrayOf(0x80.toByte(), 0xD6.toByte(), 0x00, 0x01)

/**
 * Answers a RAOP / realtime AirPlay 2 receiver's requests to re-send lost audio packets.
 *
 * The sender [record]s every audio datagram exactly as it went out (encrypted ones
 * included, as re-encrypting would advance the nonce); [serve] reads the receiver's resend
 * requests from the control [socket] and re-sends the packets still in the history.
 */
class RtpRetransmitter(private val socket: DatagramSocket) {
    private val history = arrayOfNulls<ByteArray>(HISTORY)
    private val historySequence = IntArray(HISTORY) { -1 }

    @Volatile var packetsResent = 0L
        private set

    fun record(sequence: Int, datagram: ByteArray) {
        val seq = sequence and SEQUENCE_MASK
        synchronized(history) {
            history[seq % HISTORY] = datagram
            historySequence[seq % HISTORY] = seq
        }
    }

    /** Blocks answering resend requests until [isRunning] turns false or the socket closes. */
    fun serve(isRunning: () -> Boolean) {
        val buffer = ByteArray(MAX_CONTROL_PACKET)
        try {
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            while (isRunning()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (isResendRequest(buffer, packet.length)) {
                    resend(
                        first = u16(buffer, RESEND_FIRST_SEQ_OFFSET),
                        count = u16(buffer, RESEND_COUNT_OFFSET).coerceAtMost(HISTORY),
                        reply = packet
                    )
                }
            }
        } catch (_: IOException) {
            // Socket closed: the session ended.
        }
    }

    private fun isResendRequest(data: ByteArray, length: Int) = length >= RESEND_REQUEST_BYTES &&
        (data[1].toInt() and PAYLOAD_TYPE_MASK) == PT_RESEND_REQUEST

    private fun resend(first: Int, count: Int, reply: DatagramPacket) {
        for (i in 0 until count) {
            val seq = (first + i) and SEQUENCE_MASK
            val original = synchronized(history) {
                history[seq % HISTORY].takeIf { historySequence[seq % HISTORY] == seq }
            } ?: continue
            val out = RESEND_HEADER + original
            socket.send(DatagramPacket(out, out.size, reply.address, reply.port))
            packetsResent++
        }
    }

    private fun u16(data: ByteArray, offset: Int) =
        ByteBuffer.wrap(data, offset, 2).short.toInt() and SEQUENCE_MASK
}
