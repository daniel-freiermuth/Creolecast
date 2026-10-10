package com.creolecast.app.airplay2

import android.util.Log
import java.io.IOException
import java.security.SecureRandom

/**
 * Reuse the stored long-term identity when we have one, exactly as
 * airplay2.go `setupAirPlay2` does: pair-verify with the saved keys, and only
 * fall back to a fresh pair-setup when that verification fails.
 */
internal class AirPlay2Pairing(
    private val config: AirPlay2ClientConfig,
    private val control: AirPlay2ControlChannel,
    private val receiver: AirPlay2ReceiverInfo,
    random: SecureRandom
) {
    private companion object {
        const val TAG = "AirPlay2Client"
    }

    private val session = PairingSession(control, config.clientName)
    private val pairSetup = HapPairSetup(session, control, random)
    private val pairVerify = HapPairVerify(session, control, receiver)

    /**
     * Leaves the control channel keyed, or returns false. [reconnect] opens a
     * fresh control connection and re-reads /info.
     *
     * @throws NeedsPinException when the receiver wants a PIN we don't have.
     */
    fun establish(reconnect: () -> Boolean): Boolean {
        val saved = config.credentialStore.load(receiver.deviceId)
        // A rejected pair-verify usually leaves the receiver's connection
        // unusable, so start a fresh one before re-pairing (airplay2.go
        // setupAirPlay2 reconnects on this path).
        return when {
            saved == null -> pairFresh()
            verifySaved(saved) -> true
            else -> reconnect() && pairFresh()
        }
    }

    private fun verifySaved(saved: AirPlay2Credentials): Boolean {
        session.credentials = saved
        session.pairType = if (config.password != null) {
            PairingSession.HKP_SCREEN_CAPTURE
        } else {
            PairingSession.HKP_TRANSIENT
        }
        val verified = pairVerify.run(saved)
        if (!verified) {
            Log.w(TAG, "pair-verify with saved credentials failed, re-pairing")
            config.credentialStore.clear(receiver.deviceId)
        }
        return verified
    }

    private fun pairFresh(): Boolean {
        val fresh = AirPlay2Credentials.generate()
        session.credentials = fresh

        if (!pairSetup.run(config.password, fresh)) {
            if (config.password == null) {
                // Ask the receiver to show its PIN before the UI prompts for it,
                // otherwise an Apple TV never displays one (pairing.go
                // StartPINDisplay).
                startPinDisplay()
                throw NeedsPinException(config.host)
            }
            return false
        }

        // Transient pair-setup ends at M4 with the channel already keyed from the
        // SRP session key; pair-verify only follows a persistent PIN pairing.
        return session.pairType == PairingSession.HKP_TRANSIENT || verifyNewPairing(fresh)
    }

    private fun verifyNewPairing(fresh: AirPlay2Credentials): Boolean {
        val verified = pairVerify.run(fresh)
        if (verified) {
            config.credentialStore.save(receiver.deviceId, fresh)
        } else {
            Log.e(TAG, "pair-verify failed after pair-setup")
        }
        return verified
    }

    /** Ask the receiver to display a pairing PIN. HTTP 453 means "accepted". */
    private fun startPinDisplay() {
        try {
            session.pairType = PairingSession.HKP_SCREEN_CAPTURE
            val resp = control.request(
                "POST",
                "/pair-pin-start",
                null,
                null,
                listOf(
                    "X-Apple-HKP" to session.pairType.toString(),
                    "X-Apple-SupportedPINLengths" to "4"
                )
            )
            Log.d(TAG, "pair-pin-start: ${resp.code}")
        } catch (e: IOException) {
            Log.w(TAG, "pair-pin-start failed", e)
        }
    }
}

/** Pairing state shared by pair-setup and pair-verify: the HKP flavour and our identity. */
internal class PairingSession(
    private val control: AirPlay2ControlChannel,
    private val clientName: String
) {
    companion object {
        /** X-Apple-HKP values (pairing.go pairingType* constants). */
        const val HKP_LEGACY = 3
        const val HKP_TRANSIENT = 4
        const val HKP_SCREEN_CAPTURE = 5
    }

    var pairType = HKP_TRANSIENT
    var credentials: AirPlay2Credentials? = null

    fun post(path: String, body: ByteArray, verify: Boolean = false): RtspResponse {
        val headers = mutableListOf<Pair<String, String>>()
        if (pairType == HKP_LEGACY) {
            headers += "X-Apple-HKP" to HKP_LEGACY.toString()
        } else {
            headers += "X-Apple-Client-Name" to clientName
            headers += "X-Apple-HKP" to pairType.toString()
            credentials?.let { headers += "X-Apple-Client-ID" to it.pairingId }
            if (verify) headers += "X-Apple-PD" to "1"
        }
        return control.request("POST", path, "application/octet-stream", body, headers)
    }
}
