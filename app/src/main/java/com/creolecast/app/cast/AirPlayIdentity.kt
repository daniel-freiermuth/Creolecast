package com.creolecast.app.cast

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale
import java.util.UUID

/**
 * The identifiers this sender presents to AirPlay receivers. Each is generated once and
 * persisted in [prefs], so receivers see the same sender across casts.
 */
internal class AirPlayIdentity(private val prefs: SharedPreferences) {
    /**
     * DACP-ID / Client-Instance: 16 upper-case hex digits, the first not 0: shairport-sync
     * drops leading zeros from the `iTunes_Ctrl_<DACP-ID>` name it looks up, so an id with
     * one never matches and DACP remote control never reaches us. Such ids stored by older
     * versions are replaced.
     */
    val dacpId: String by lazy {
        prefs.getString("dacp_id", null)?.takeUnless { it.startsWith('0') }
            ?: generateDacpId().also { prefs.edit { putString("dacp_id", it) } }
    }

    /** Active-Remote: an 8-digit decimal token. */
    val activeRemote: String by lazy {
        stored("active_remote") { (10000000..99999999).random().toString() }
    }

    /** AirPlay DeviceID: six colon-separated hex octets, MAC-address style. */
    val deviceId: String by lazy {
        stored("airplay_device_id") {
            (1..6).joinToString(":") { "%02X".format(Locale.US, (0..255).random()) }
        }
    }

    private fun generateDacpId(): String {
        val hex = UUID.randomUUID().toString().replace("-", "").uppercase()
        return hex.trimStart('0').take(16).padEnd(16, 'F')
    }

    private fun stored(key: String, generate: () -> String): String =
        prefs.getString(key, null) ?: generate().also { prefs.edit { putString(key, it) } }
}
