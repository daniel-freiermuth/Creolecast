package com.creolecast.app.raop

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RtpRetransmitterTest {
    @Test
    fun `resends the requested packets still in history, across the sequence wrap`() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { senderControl ->
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
                val retransmitter = RtpRetransmitter(senderControl)
                retransmitter.record(0xFFFF, byteArrayOf(1))
                retransmitter.record(0x10000, byteArrayOf(2)) // wraps to sequence 0
                // Sequence 1 was never sent.
                val running = AtomicBoolean(true)
                val server = thread { retransmitter.serve { running.get() } }

                // Resend request: 80 D5, own seq, first missing seq 0xFFFF, count 3.
                val request = byteArrayOf(0x80.toByte(), 0xD5.toByte(), 0, 1, -1, -1, 0, 3)
                receiver.send(
                    DatagramPacket(request, request.size, senderControl.localSocketAddress)
                )

                receiver.soTimeout = 2000
                val resent = List(2) {
                    val packet = DatagramPacket(ByteArray(64), 64)
                    receiver.receive(packet)
                    packet.data.copyOf(packet.length)
                }
                assertArrayEquals(byteArrayOf(0x80.toByte(), 0xD6.toByte(), 0, 1, 1), resent[0])
                assertArrayEquals(byteArrayOf(0x80.toByte(), 0xD6.toByte(), 0, 1, 2), resent[1])

                receiver.soTimeout = 300
                val nothingMore = try {
                    receiver.receive(DatagramPacket(ByteArray(64), 64))
                    false
                } catch (_: SocketTimeoutException) {
                    true
                }
                assertEquals(true, nothingMore)
                assertEquals(2L, retransmitter.packetsResent)
                running.set(false)
                server.join()
            }
        }
    }
}
