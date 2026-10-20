package com.creolecast.app.cast

import com.creolecast.app.AudioCastService
import com.creolecast.app.raop.AlacEncoder
import com.creolecast.app.raop.RaopCrypto
import com.creolecast.app.raop.RtpRetransmitter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.Flow

/** 352 stereo 16-bit samples: one ALAC frame. */
private const val RAOP_FRAME_BYTES = 1408

/** Bytes per stereo 16-bit sample frame; RTP timestamps advance in samples. */
private const val BYTES_PER_STEREO_SAMPLE = 4
private const val RTP_HEADER_BYTES = 12

/** RTP version 2, no padding/extension/CSRC. */
private const val RTP_VERSION_2 = 0x80

/** Payload type 0x60 (96); the first packet of the stream also sets the marker bit. */
private const val RTP_AUDIO_PAYLOAD = 0x60
private const val RTP_AUDIO_PAYLOAD_WITH_MARKER = 0xE0

/**
 * Sends captured PCM to a RAOP receiver as RTP over UDP: 1408-byte ALAC "uncompressed"
 * frames, AES-encrypted when the session negotiated a key. Each packet is kept in
 * [retransmitter] for re-sending.
 */
internal class RaopAudioSender(
    private val socket: DatagramSocket,
    private val serverAddress: InetAddress,
    private val serverAudioPort: Int,
    private val rtpSessionId: Int,
    private val crypto: RaopCrypto?,
    private val retransmitter: RtpRetransmitter
) {
    private var sequence = 0
    private var timestamp = AudioCastService.LATENCY
    private var isFirstPacket = true
    private val framer = PcmFramer(RAOP_FRAME_BYTES, ::send)

    /** Streams [audio] (captured at 44100 Hz natively, so no resampling) until cancelled. */
    suspend fun stream(audio: Flow<ByteArray>) {
        audio.collect { framer.push(it) }
    }

    private fun send(pcmFrame: ByteArray) {
        var alacFrame = AlacEncoder.encodeUncompressed(pcmFrame, hasSize = false)
        crypto?.let { alacFrame = it.encryptAudio(alacFrame) }

        val rtpHeader = ByteBuffer.allocate(RTP_HEADER_BYTES).apply {
            put(RTP_VERSION_2.toByte())
            put((if (isFirstPacket) RTP_AUDIO_PAYLOAD_WITH_MARKER else RTP_AUDIO_PAYLOAD).toByte())
            putShort(sequence++.toShort())
            putInt(timestamp)
            putInt(rtpSessionId)
        }
        timestamp += RAOP_FRAME_BYTES / BYTES_PER_STEREO_SAMPLE // 352 samples
        isFirstPacket = false

        val payload = rtpHeader.array() + alacFrame
        retransmitter.record(sequence - 1, payload)
        socket.send(DatagramPacket(payload, payload.size, serverAddress, serverAudioPort))
    }
}
