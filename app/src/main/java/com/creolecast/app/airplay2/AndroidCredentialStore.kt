package com.creolecast.app.airplay2

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.core.content.edit

/** Secure-prefs key prefix of AirPlay 2 pairings, by the receiver's device id. */
const val AIRPLAY2_CREDENTIAL_PREFIX = "airplay2_cred_"

/** Persists AirPlay 2 pairing identities in SharedPreferences. */
class AndroidCredentialStore(private val prefs: SharedPreferences) : AirPlay2CredentialStore {

    // Value layout: base64( UTF-8 of "<pairingId>\u0000<base64 seed>\u0000<base64 public>" ),
    // all Base64 with NO_WRAP so the record stays a single line.
    override fun load(deviceId: String): AirPlay2Credentials? = try {
        prefs.getString(keyFor(deviceId), null)?.let(::decodeRecord)
    } catch (e: IllegalArgumentException) {
        // Bad Base64 in the stored record: treat as "not paired" and pair again.
        Log.w(TAG, "Ignoring corrupt AirPlay 2 pairing record for $deviceId", e)
        null
    } catch (e: ClassCastException) {
        // The preference key holds a non-String value: treat as "not paired".
        Log.w(TAG, "Ignoring non-string AirPlay 2 pairing record for $deviceId", e)
        null
    }

    private fun decodeRecord(stored: String): AirPlay2Credentials? {
        val parts = String(
            Base64.decode(stored, Base64.NO_WRAP),
            Charsets.UTF_8
        ).split('\u0000')
        if (parts.size != RECORD_FIELD_COUNT) return null
        val pairingId = parts[0]
        val seed = Base64.decode(parts[1], Base64.NO_WRAP)
        val pub = Base64.decode(parts[2], Base64.NO_WRAP)
        val valid = pairingId.isNotEmpty() && seed.size == ED25519_KEY_SIZE &&
            pub.size == ED25519_KEY_SIZE
        return if (valid) AirPlay2Credentials(pairingId, seed, pub) else null
    }

    override fun save(deviceId: String, credentials: AirPlay2Credentials) {
        val record = credentials.pairingId +
            '\u0000' + Base64.encodeToString(credentials.ed25519Seed, Base64.NO_WRAP) +
            '\u0000' + Base64.encodeToString(credentials.ed25519Public, Base64.NO_WRAP)
        val encoded = Base64.encodeToString(record.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        prefs.edit { putString(keyFor(deviceId), encoded) }
    }

    override fun clear(deviceId: String) {
        prefs.edit { remove(keyFor(deviceId)) }
    }

    private fun keyFor(deviceId: String): String = AIRPLAY2_CREDENTIAL_PREFIX + deviceId

    private companion object {
        const val TAG = "AndroidCredentialStore"

        // pairingId, seed, public key
        const val RECORD_FIELD_COUNT = 3

        // Ed25519 seed and public key are both 32 bytes.
        const val ED25519_KEY_SIZE = 32
    }
}
