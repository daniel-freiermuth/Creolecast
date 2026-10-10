package com.creolecast.app.airplay2

import java.util.UUID

/** Inputs for [BinaryPlist.makeSessionPlist] (the session-level RTSP SETUP body). */
data class SessionPlistParams(
    val sessionUuid: UUID,
    val deviceId: String,
    val name: String,
    val model: String,
    val sourceVersion: String,
    val timingProtocol: String,
    val timingPort: Int,
    val timingPeerId: String? = null,
    val timingPeerAddress: String? = null
)

/** Inputs for [BinaryPlist.makeStreamPlist] (the audio-stream RTSP SETUP body). */
data class StreamPlistParams(
    val controlPort: Int,
    val shk: ByteArray?,
    val streamConnectionId: Long,
    val sampleRate: Int,
    val spf: Int,
    val latencyMin: Long,
    val latencyMax: Long,
    val useStreamConnections: Boolean
)
