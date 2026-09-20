package com.creolecast.app.airplay2

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Long-term HomeKit pairing identity this sender presents to one receiver. */
class AirPlay2Credentials(
    val pairingId: String,        // stable UUID string, our controller identifier
    val ed25519Seed: ByteArray,   // 32 bytes, private
    val ed25519Public: ByteArray  // 32 bytes
) {
    companion object {
        fun generate(): AirPlay2Credentials {
            val seed = ByteArray(32)
            SecureRandom().nextBytes(seed)
            return AirPlay2Credentials(
                pairingId = UUID.randomUUID().toString().uppercase(),
                ed25519Seed = seed,
                ed25519Public = AirPlay2Crypto.ed25519PublicFromSeed(seed)
            )
        }
    }
}

/** Per-receiver persistence, keyed by the receiver's AirPlay DeviceID. */
interface AirPlay2CredentialStore {
    fun load(deviceId: String): AirPlay2Credentials?
    fun save(deviceId: String, credentials: AirPlay2Credentials)
    fun clear(deviceId: String)
}

/** In-memory store; used when no persistent store is supplied. */
class InMemoryCredentialStore : AirPlay2CredentialStore {

    private val entries = ConcurrentHashMap<String, AirPlay2Credentials>()

    override fun load(deviceId: String): AirPlay2Credentials? = entries[deviceId]

    override fun save(deviceId: String, credentials: AirPlay2Credentials) {
        entries[deviceId] = credentials
    }

    override fun clear(deviceId: String) {
        entries.remove(deviceId)
    }
}
