package com.creolecast.app.airplay2

/** 85 ms at 44100 Hz, truncated — latency.go samplesFor44k1: lowest delay, no resends. */
const val AIRPLAY2_LOW_LATENCY_SAMPLES = 3748

/** 0.5 s: long enough for shairport-sync to request lost packets (it needs > 0.2 s). */
const val AIRPLAY2_STABLE_LATENCY_SAMPLES = 22050

/** The receiver to stream to and how this sender presents itself to it. */
data class AirPlay2ClientConfig(
    val host: String,
    val port: Int,
    /** Our AirPlay device id (`AA:BB:CC:DD:EE:FF`); also seeds our PTP clock identity. */
    val deviceId: String,
    val dacpId: String,
    val activeRemote: String,
    val sampleRate: Int = 44100,
    /** Samples per ALAC frame, i.e. per RTP packet. */
    val frameSize: Int = 352,
    /** Pairing PIN; null starts with transient (PIN-less) pairing. */
    val password: String? = null,
    /** The receiver's Ed25519 key from its mDNS TXT record, if it publishes one. */
    val txtPk: ByteArray? = null,
    val credentialStore: AirPlay2CredentialStore = InMemoryCredentialStore(),
    val clientName: String = "CreoleCast",
    /** Volume set when streaming starts: -30.0 to 0.0 dB, or -144.0 for mute. */
    val initialVolumeDb: Double = 0.0,
    /** How far ahead of playback audio is sent, in samples; also the resend window. */
    val latencySamples: Int = AIRPLAY2_LOW_LATENCY_SAMPLES
)
