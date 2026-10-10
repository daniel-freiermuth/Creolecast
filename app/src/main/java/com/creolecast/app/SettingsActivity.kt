package com.creolecast.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private var packetLogClickCount = 0
    private var lastClickTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.protocolsLayout).setOnClickListener {
            startActivity(Intent(this, ProtocolsActivity::class.java))
        }

        findViewById<View>(R.id.airplayPinsLayout).setOnClickListener {
            startActivity(Intent(this, AirPlayPinsActivity::class.java))
        }

        findViewById<View>(R.id.notificationAccessLayout).setOnClickListener {
            showNotificationAccessExplanationDialog()
        }

        findViewById<View>(R.id.packetLogLayout).setOnClickListener {
            startActivity(Intent(this, PacketLogActivity::class.java))
        }

        findViewById<View>(R.id.resetServerLayout).setOnClickListener {
            resetLastServer()
        }

        findViewById<View>(R.id.resetServerLayout).setOnLongClickListener {
            handlePacketLogClick()
            true
        }
    }

    private fun handlePacketLogClick() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime > 2000) {
            packetLogClickCount = 1
        } else {
            packetLogClickCount++
        }
        lastClickTime = currentTime

        if (packetLogClickCount >= 3) {
            packetLogClickCount = 0
            startActivity(Intent(this, PacketLogActivity::class.java))
        } else {
            val remaining = 3 - packetLogClickCount
            val message = if (remaining == 1) {
                getString(R.string.secret_logs_one_tap)
            } else {
                getString(R.string.secret_logs_multiple_taps, remaining)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetLastServer() {
        val sharedPreferences =
            getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        sharedPreferences.edit().apply {
            remove(AudioCastService.KEY_LAST_SERVER_HOST)
            remove(AudioCastService.KEY_LAST_SERVER_PORT)
            remove(AudioCastService.KEY_LAST_SERVER_NAME)
            apply()
        }
        Toast.makeText(this, getString(R.string.server_cleared), Toast.LENGTH_SHORT).show()
    }
}
