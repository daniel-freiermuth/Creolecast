package com.creolecast.app.cast

import java.io.ByteArrayOutputStream

/**
 * Re-chunks the captured PCM byte stream into contiguous frames of exactly [frameBytes]
 * bytes, carrying any remainder over to the next [push], and hands each to [onFrame].
 */
internal class PcmFramer(private val frameBytes: Int, private val onFrame: (ByteArray) -> Unit) {
    private val pending = ByteArrayOutputStream(frameBytes * 2)

    fun push(pcm: ByteArray) {
        pending.write(pcm)
        val buffered = pending.toByteArray()
        var consumed = 0
        while (consumed + frameBytes <= buffered.size) {
            val frame = buffered.copyOfRange(consumed, consumed + frameBytes)
            consumed += frameBytes
            onFrame(frame)
        }
        pending.reset()
        if (consumed < buffered.size) {
            pending.write(buffered, consumed, buffered.size - consumed)
        }
    }
}
