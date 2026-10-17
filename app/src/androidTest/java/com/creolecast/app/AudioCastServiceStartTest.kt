package com.creolecast.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioCastServiceStartTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Before
    fun grantProjection() {
        // Stands in for the user accepting the screen-capture dialog, which MainActivity
        // requires before it starts the service.
        instrumentation.uiAutomation
            .executeShellCommand("appops set ${context.packageName} PROJECT_MEDIA allow")
            .close()
    }

    @Test
    fun startWithUnusableDestinationStopsServiceInsteadOfCrashing() {
        // What MainActivity sends for a discovered receiver that advertised no port.
        val intent = Intent(context, AudioCastService::class.java).apply {
            action = AudioCastService.ACTION_START
            putExtra(AudioCastService.EXTRA_MEDIA_PROJECTION_TOKEN, Intent())
            putExtra(AudioCastService.EXTRA_SERVER_HOST, "192.0.2.1")
            putExtra(AudioCastService.EXTRA_SERVER_PORT, 0)
            putExtra(AudioCastService.EXTRA_SERVER_NAME, "Living Room")
            putExtra(AudioCastService.EXTRA_SERVER_PLATFORM, "AriaCast")
        }
        context.startForegroundService(intent)

        // Outlast the system's startForeground() deadline (service_start_foreground_timeout_ms,
        // 30 s on API 35) and the crash/ANR that follows it: a service that misses the
        // deadline takes the whole process down.
        Thread.sleep(FOREGROUND_DEADLINE_MS + 15_000)

        assertFalse("AudioCastService still running", isServiceRunning())
    }

    @Suppress("DEPRECATION") // Still returns the caller's own services.
    private fun isServiceRunning(): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == AudioCastService::class.java.name }
    }

    private companion object {
        const val FOREGROUND_DEADLINE_MS = 30_000L
    }
}
