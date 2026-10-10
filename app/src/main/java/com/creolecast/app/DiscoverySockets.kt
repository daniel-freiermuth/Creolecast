package com.creolecast.app

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/**
 * Receives one datagram into [buffer], or returns null once the socket's timeout elapses
 * (the expected end of a discovery listen window) or the socket fails — either way
 * there is nothing more to receive right now.
 */
internal fun DatagramSocket.receiveOrNull(buffer: ByteArray, tag: String): DatagramPacket? {
    val packet = DatagramPacket(buffer, buffer.size)
    return try {
        receive(packet)
        packet
    } catch (expected: SocketTimeoutException) {
        null
    } catch (e: IOException) {
        Log.d(tag, "Discovery socket receive failed", e)
        null
    }
}
