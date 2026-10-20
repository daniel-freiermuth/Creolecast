package com.creolecast.app.cast

import android.content.SharedPreferences
import androidx.core.content.edit

/** Secure-prefs key prefix of a receiver's access password, by host. */
internal const val AIRPLAY_PASSWORD_PREFIX = "airplay_password_"

/** Access passwords of password-protected AirPlay receivers, kept in the secure prefs. */
internal class AirPlayPasswords(private val prefs: SharedPreferences) {
    operator fun get(host: String): String? = prefs.getString(AIRPLAY_PASSWORD_PREFIX + host, null)

    operator fun set(host: String, password: String) {
        prefs.edit { putString(AIRPLAY_PASSWORD_PREFIX + host, password) }
    }

    fun remove(host: String) {
        prefs.edit { remove(AIRPLAY_PASSWORD_PREFIX + host) }
    }
}
