package com.creolecast.app.airplay2

import android.util.Log
import java.net.HttpURLConnection
import java.security.SecureRandom

/**
 * HAP pair-setup. Transient pairing first (no PIN), PIN pairing when we have
 * one. Transient pair-setup ends after M4: the SRP session key keys the control
 * channel and no long-term identity is exchanged (HAP; shairport-sync rtsp.c
 * handle_pair_setup). PIN pairing runs the full M1..M6 exchange, because M5 is
 * what registers our long-term key that pair-verify later signs with.
 */
internal class HapPairSetup(
    private val session: PairingSession,
    private val control: AirPlay2ControlChannel,
    private val random: SecureRandom
) {
    private companion object {
        const val TAG = "AirPlay2Client"
        const val PAIR_SETUP_PATH = "/pair-setup"

        /** Fixed SRP password for HomeKit transient pair-setup. */
        const val TRANSIENT_PIN = "3939"
    }

    fun run(pin: String?, credentials: AirPlay2Credentials): Boolean {
        val attempts = if (pin != null) {
            listOf(PairingSession.HKP_SCREEN_CAPTURE to pin, PairingSession.HKP_LEGACY to pin)
        } else {
            // HomeKit transient pairing still runs SRP, with the fixed password
            // "3939" (shairport-sync, pyatv and owntone all use it); an empty
            // password makes the receiver's M4 proof fail to verify.
            listOf(PairingSession.HKP_TRANSIENT to TRANSIENT_PIN)
        }
        val paired = attempts.any { (type, srpPin) -> attempt(type, srpPin, credentials) }
        if (!paired) Log.e(TAG, "Pair-setup: all attempts failed")
        return paired
    }

    private fun attempt(type: Int, srpPin: String, credentials: AirPlay2Credentials): Boolean {
        session.pairType = type
        val transient = type == PairingSession.HKP_TRANSIENT
        Log.d(TAG, "Pair-setup attempt: hkp=$type transient=$transient")
        val srp = srpExchange(srpPin, transient)
        return when {
            srp == null -> false
            transient -> finishTransient(srp)
            else -> finishPersistent(srp, type, credentials)
        }
    }

    /** M1..M4; the SRP client once the receiver's M4 proof checked out, else null. */
    private fun srpExchange(srpPin: String, transient: Boolean): SRP6aClient? {
        val srp = SRP6aClient(srpPin, "Pair-Setup", random)
        val m2 = step("M1", srp.buildM1(transient))?.takeUnless(::reportsM2Error)
        val m4 = m2?.let(srp::processM2)?.let { step("M3", it) }
        val verified = m4 != null && srp.verifyM4(m4)
        if (m4 != null && !verified) Log.e(TAG, "pair-setup M4 verification failed")
        return srp.takeIf { verified }
    }

    private fun reportsM2Error(m2: ByteArray): Boolean {
        val error = TlvUtil.parse(m2)[TlvUtil.TLV_ERROR]?.firstOrNull()
        if (error != null) Log.e(TAG, "pair-setup M2 error: ${error[0].toInt()}")
        return error != null
    }

    /** POSTs [message]; the reply body, or null when the receiver refused it. */
    private fun step(message: String, body: ByteArray): ByteArray? {
        val resp = session.post(PAIR_SETUP_PATH, body)
        val accepted = resp.code == HttpURLConnection.HTTP_OK
        if (!accepted) Log.e(TAG, "pair-setup $message failed: ${resp.code}")
        return resp.body?.takeIf { accepted }
    }

    private fun finishTransient(srp: SRP6aClient): Boolean {
        val sharedKey = srp.sharedKeyBytes
        if (sharedKey != null) {
            control.enableEncryption(sharedKey)
            Log.d(TAG, "Pair-setup complete (transient), control channel encrypted")
        }
        return sharedKey != null
    }

    private fun finishPersistent(
        srp: SRP6aClient,
        type: Int,
        credentials: AirPlay2Credentials
    ): Boolean {
        val m5 = srp.buildM5(
            credentials,
            includeScreenCaptureAcl = type == PairingSession.HKP_SCREEN_CAPTURE
        )
        val m6 = m5?.let { step("M5", it) }
        val accepted = m6 != null && srp.verifyM6(m6)
        if (m6 != null && !accepted) Log.e(TAG, "pair-setup M6 reported an error")
        if (accepted) Log.d(TAG, "Pair-setup complete (hkp=$type)")
        return accepted
    }
}
