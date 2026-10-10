package com.creolecast.app.airplay2

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
    val clientName: String = "CreoleCast"
)
