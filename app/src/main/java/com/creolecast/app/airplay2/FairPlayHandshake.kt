package com.creolecast.app.airplay2

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.security.SecureRandom

/**
 * FairPlay SAP. Receivers advertising feature bit 14 reject SETUP with RTSP 455
 * until /fp-setup completes (fairplay.go FairPlaySetup).
 */
internal class FairPlayHandshake(
    private val control: AirPlay2ControlChannel,
    private val random: SecureRandom
) {
    private companion object {
        const val TAG = "AirPlay2Client"
        val HEADERS = listOf("X-Apple-ET" to "32")
    }

    /** False when the receiver rejected the handshake or it could not be completed. */
    fun run(): Boolean = try {
        exchange()
    } catch (e: IOException) {
        failed(e)
    } catch (e: IllegalArgumentException) {
        // FairPlaySapSession rejects malformed m2/m4 records with these two.
        failed(e)
    } catch (e: IllegalStateException) {
        failed(e)
    }

    private fun exchange(): Boolean {
        val session = FairPlaySapSession(random)
        val r1 = post(session.message1())
        val m2 = r1.body
        return when (r1.code) {
            HttpURLConnection.HTTP_NOT_FOUND -> {
                Log.w(TAG, "/fp-setup not implemented despite the FPSAP feature bit; continuing")
                true
            }

            HttpURLConnection.HTTP_OK -> m2 != null && finish(session, m2)

            else -> {
                Log.e(TAG, "fp-setup m1 failed: ${r1.code}")
                false
            }
        }
    }

    private fun finish(session: FairPlaySapSession, m2: ByteArray): Boolean {
        val r2 = post(session.exchangeM3(m2))
        val accepted = r2.code == HttpURLConnection.HTTP_OK
        if (!accepted) Log.e(TAG, "fp-setup m3 failed: ${r2.code}")
        val m4 = r2.body?.takeIf { accepted }
        if (m4 != null) {
            session.finish(m4)
            Log.d(TAG, "FairPlay SAP handshake complete")
        }
        return m4 != null
    }

    private fun post(body: ByteArray): RtspResponse =
        control.request("POST", "/fp-setup", "application/octet-stream", body, HEADERS)

    private fun failed(e: Exception): Boolean {
        Log.e(TAG, "FairPlay SAP failed", e)
        return false
    }
}
