package com.creolecast.app.airplay2

import android.content.SharedPreferences
import android.util.Base64

/** Persists AirPlay 2 pairing identities in SharedPreferences. */
class AndroidCredentialStore(private val prefs: SharedPreferences) : AirPlay2CredentialStore {

    // Value layout: base64( UTF-8 of "<pairingId>\u0000<base64 seed>\u0000<base64 public>" ),
    // all Base64 with NO_WRAP so the record stays a single line.
    override fun load(deviceId: String): AirPlay2Credentials? {
        return try {
            val stored = prefs.getString(keyFor(deviceId), null) ?: return null
            val parts = String(Base64.decode(stored, Base64.NO_WRAP), Charsets.UTF_8).split('\u0000')
            if (parts.size != 3) return null
            val pairingId = parts[0]
            val seed = Base64.decode(parts[1], Base64.NO_WRAP)
            val pub = Base64.decode(parts[2], Base64.NO_WRAP)
            if (pairingId.isEmpty() || seed.size != 32 || pub.size != 32) return null
            AirPlay2Credentials(pairingId, seed, pub)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: ClassCastException) {
            null
        }
    }

    override fun save(deviceId: String, credentials: AirPlay2Credentials) {
        val record = credentials.pairingId +
            '\u0000' + Base64.encodeToString(credentials.ed25519Seed, Base64.NO_WRAP) +
            '\u0000' + Base64.encodeToString(credentials.ed25519Public, Base64.NO_WRAP)
        val encoded = Base64.encodeToString(record.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        prefs.edit().putString(keyFor(deviceId), encoded).apply()
    }

    override fun clear(deviceId: String) {
        prefs.edit().remove(keyFor(deviceId)).apply()
    }

    private fun keyFor(deviceId: String): String = "airplay2_cred_$deviceId"
}
