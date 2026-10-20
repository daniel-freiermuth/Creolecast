package com.creolecast.app.cast

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.creolecast.app.AudioCastService
import com.creolecast.app.CastDestination
import com.creolecast.app.CastState
import com.creolecast.app.ExtraFields
import com.creolecast.app.MediaCommand
import com.creolecast.app.R
import com.creolecast.app.airplay2.AIRPLAY2_LOW_LATENCY_SAMPLES
import com.creolecast.app.airplay2.AIRPLAY2_STABLE_LATENCY_SAMPLES
import com.creolecast.app.airplay2.AirPlay2Client
import com.creolecast.app.airplay2.AirPlay2ClientConfig
import com.creolecast.app.airplay2.AndroidCredentialStore
import com.creolecast.app.airplay2.NeedsPinException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Default AirPlay 2 control port when the receiver advertises none. */
private const val AIRPLAY_PORT = 7000

/** ALAC frame length in samples for AirPlay 2. */
private const val ALAC_FRAME_SAMPLES = 352

/** One ALAC frame of 16-bit stereo PCM: 352 samples * 2 channels * 2 bytes. */
private const val ALAC_FRAME_BYTES = ALAC_FRAME_SAMPLES * 2 * 2

/** TXT `pk`: the receiver's 32-byte Ed25519 public key as 64 hex characters. */
private const val ED25519_KEY_BYTES = 32
private const val HEX_RADIX = 16

/**
 * AirPlay 2 casts: connects an [AirPlay2Client] (prompting for, and remembering, a PIN
 * when the receiver requires pairing), then streams the captured PCM to it.
 *
 * Pairing PINs are kept in the backup-excluded [securePrefs] under `airplay2_pin_<host>`.
 */
internal class AirPlay2Session(
    private val ctx: CastContext,
    private val identity: AirPlayIdentity,
    private val securePrefs: SharedPreferences,
    private val context: Context
) {
    private sealed interface ConnectStep {
        data class Retry(val pin: String?, val clearPinOnFailure: Boolean) : ConnectStep
        data class Connected(val client: AirPlay2Client, val pin: String?) : ConnectStep
        data object Failed : ConnectStep
    }

    /**
     * Casts to [dest] until cancelled. This is the session's failure boundary:
     * [AirPlay2Client] surfaces I/O, pairing-crypto and protocol failures as arbitrary
     * exception types, and any of them must end the session in [CastState.ERROR] rather
     * than crash the service.
     */
    @Suppress("TooGenericExceptionCaught") // Session failure boundary, see KDoc.
    suspend fun cast(dest: CastDestination) = withContext(ctx.ioDispatcher) {
        try {
            ctx.state.value = CastState.CONNECTING
            val client = connect(dest)
            if (client != null) stream(dest, client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "AirPlay 2 session failed for ${dest.name}: ${e.reason}")
            ctx.state.value = CastState.ERROR
        }
    }

    /** Connects, re-trying without a rejected saved PIN or with a PIN the user entered. */
    private suspend fun connect(dest: CastDestination): AirPlay2Client? {
        val txtPk = parseTxtPk(dest.extra)
        // Long-term HomeKit pairing identities, keyed by the receiver's AirPlay DeviceID, so
        // pair-verify can reuse the key pair pair-setup registered instead of re-pairing.
        val credentialStore = AndroidCredentialStore(securePrefs)
        val savedPin = securePrefs.getString(pinKey(dest.host), null)
        var retry: ConnectStep.Retry? = ConnectStep.Retry(savedPin, savedPin != null)
        var outcome: ConnectStep = ConnectStep.Failed
        while (retry != null) {
            outcome = attempt(dest, txtPk, credentialStore, retry)
            retry = outcome as? ConnectStep.Retry
        }
        val connected = outcome as? ConnectStep.Connected
        // Persist the PIN so the user isn't asked again next time.
        connected?.pin?.let { pin -> securePrefs.edit { putString(pinKey(dest.host), pin) } }
        return connected?.client
    }

    private suspend fun attempt(
        dest: CastDestination,
        txtPk: ByteArray?,
        credentialStore: AndroidCredentialStore,
        retry: ConnectStep.Retry
    ): ConnectStep {
        val client = newClient(dest, retry.pin, txtPk, credentialStore)
        val connected = try {
            client.connect()
        } catch (e: NeedsPinException) {
            client.close()
            return promptForPin(e.host)
        }
        return when {
            connected -> ConnectStep.Connected(client, retry.pin)

            retry.clearPinOnFailure -> {
                // The saved PIN was rejected: forget it and retry, so that
                // NeedsPinException prompts the user for a new one.
                securePrefs.edit { remove(pinKey(dest.host)) }
                client.close()
                ConnectStep.Retry(pin = null, clearPinOnFailure = false)
            }

            else -> {
                ctx.state.value = CastState.ERROR
                client.close()
                ConnectStep.Failed
            }
        }
    }

    private suspend fun promptForPin(host: String): ConnectStep {
        val pin = ctx.pinPrompt.request(host)
        return if (pin == null) {
            ctx.state.value = CastState.ERROR
            ConnectStep.Failed
        } else {
            ConnectStep.Retry(pin, clearPinOnFailure = false)
        }
    }

    private fun newClient(
        dest: CastDestination,
        pin: String?,
        txtPk: ByteArray?,
        credentialStore: AndroidCredentialStore
    ): AirPlay2Client {
        val client = AirPlay2Client(
            AirPlay2ClientConfig(
                host = dest.host,
                port = if (dest.port > 0) dest.port else AIRPLAY_PORT,
                deviceId = identity.deviceId,
                dacpId = identity.dacpId,
                activeRemote = identity.activeRemote,
                sampleRate = AudioCastService.captureSampleRate(dest.platform),
                frameSize = ALAC_FRAME_SAMPLES,
                password = pin,
                txtPk = txtPk,
                credentialStore = credentialStore,
                clientName = context.getString(R.string.app_name),
                initialVolumeDb = ctx.airPlayVolumeDb,
                latencySamples = latencySamples()
            )
        )
        client.eventListener = object : AirPlay2Client.EventListener {
            override fun onRemoteCommand(command: String) {
                val cmd = MediaCommand.entries.find { it.name.equals(command, ignoreCase = true) }
                if (cmd != null) ctx.scope.launch { ctx.controlCommands.emit(cmd) }
            }
        }
        return client
    }

    private suspend fun stream(dest: CastDestination, client: AirPlay2Client) {
        ctx.markCasting()
        ctx.airPlay2.register(dest.host, client)
        ctx.metadata.value?.let {
            client.sendMetadata(it.title, it.artist, it.album, ctx.artworkBytes)
        }
        val framer = PcmFramer(ALAC_FRAME_BYTES) { frame -> client.sendAudioFrame(frame) }
        try {
            ctx.audio.collect { framer.push(it) }
        } finally {
            ctx.airPlay2.unregister(dest.host)
            // This runs on cancellation too (e.g. the user hit Stop), where an ordinary
            // suspend call would immediately throw - NonCancellable lets the TEARDOWN
            // request actually reach the receiver instead of the connection just being
            // dropped. teardown() is blocking, not suspending, so it isn't preemptible
            // here; it's bounded by the client's own socket timeout.
            withContext(NonCancellable) {
                client.teardown()
                client.close()
            }
        }
    }

    /** The receiver's Ed25519 public key from TXT `pk`, or null if absent or malformed. */
    private fun parseTxtPk(extra: String?): ByteArray? {
        val pkHex = ExtraFields.get(extra, "pk")
        return if (pkHex?.length == ED25519_KEY_BYTES * 2) {
            try {
                ByteArray(ED25519_KEY_BYTES) { i ->
                    pkHex.substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte()
                }
            } catch (e: NumberFormatException) {
                Log.w(LOG_TAG, "Ignoring malformed TXT pk: ${e.reason}")
                null
            }
        } else {
            null
        }
    }

    private fun latencySamples(): Int {
        val prefs = context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.getBoolean(AudioCastService.KEY_AIRPLAY2_STABLE_STREAMING, false)) {
            AIRPLAY2_STABLE_LATENCY_SAMPLES
        } else {
            AIRPLAY2_LOW_LATENCY_SAMPLES
        }
    }

    private fun pinKey(host: String) = "airplay2_pin_$host"
}
