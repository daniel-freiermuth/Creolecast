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
    /** DACP-ID / Client-Instance: 16 upper-case hex digits. */
    val dacpId: String by lazy {
        stored("dacp_id") { UUID.randomUUID().toString().replace("-", "").take(16).uppercase() }
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

    private fun stored(key: String, generate: () -> String): String =
        prefs.getString(key, null) ?: generate().also { prefs.edit { putString(key, it) } }
}
