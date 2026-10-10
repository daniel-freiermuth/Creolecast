package com.creolecast.app.cast

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.util.Log

/** The partial wake lock that keeps capture and streaming alive with the screen off. */
internal class CastWakeLock(private val context: Context) {
    private var wakeLock: PowerManager.WakeLock? = null

    // A cast has no natural upper bound on its length; the lock is held for exactly the
    // lifetime of the cast and released by release() when it stops.
    @SuppressLint("WakelockTimeout")
    fun acquire() {
        if (wakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CreoleCast::Casting")
        }
        wakeLock?.acquire()
        Log.d(LOG_TAG, "Wake lock acquired")
    }

    fun release() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(LOG_TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }
}
