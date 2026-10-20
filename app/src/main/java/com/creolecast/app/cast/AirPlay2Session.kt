package com.creolecast.app.cast

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.creolecast.app.AudioCastService
import com.creolecast.app.CastDestination
import com.creolecast.app.CastState
import com.creolecast.app.ExtraFields
import com.creolecast.app.PacketDirection
import com.creolecast.app.PacketLogger
import com.creolecast.app.PacketType
import com.creolecast.app.airplay2.AIRPLAY2_LOW_LATENCY_SAMPLES
import com.creolecast.app.airplay2.AIRPLAY2_STABLE_LATENCY_SAMPLES
import com.creolecast.app.airplay2.AirPlay2Client
import com.creolecast.app.airplay2.AirPlay2ClientConfig
import com.creolecast.app.airplay2.AndroidCredentialStore
import com.creolecast.app.airplay2.NeedsPinException
import com.creolecast.app.airplay2.PtpUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
    /** The app's settings ([AudioCastService.PREFS_NAME]). */
    private val prefs: SharedPreferences,
    /** Our name as receivers show it. */
    private val clientName: String,
    /** The receiver changed its own volume (dB, see [airPlayVolume]). */
    private val onReceiverVolume: (Double) -> Unit
) {
    private sealed interface ConnectStep {
        data class Retry(val pin: String?) : ConnectStep
        data class Connected(val client: AirPlay2Client) : ConnectStep
        data object Failed : ConnectStep
    }

    /**
     * Casts to [dest] until cancelled, reconnecting when the receiver is lost. A receiver
     * that needs our PTP clock when this device can't run one gets AirPlay 1 instead, which
     * AirPlay 2 receivers also speak.
     */
    suspend fun cast(dest: CastDestination) = withContext(ctx.ioDispatcher) {
        ctx.state.value = CastState.CONNECTING
        var ptpUnavailable = false
        reconnecting(ctx, "AirPlay 2 ${dest.name}") {
            try {
                connectAndStream(dest)
            } catch (e: PtpUnavailableException) {
                Log.w(LOG_TAG, "AirPlay 2 ${dest.name}: ${e.reason}; falling back to AirPlay 1")
                ptpUnavailable = true
                SessionEnd.DONE
            }
        }
        if (ptpUnavailable) {
            PacketLogger.log(
                PacketDirection.OUT,
                PacketType.HANDSHAKE,
                "${dest.name}: no PTP clock possible on this device, using AirPlay 1"
            )
            RaopSession.cast(ctx, raopFallback(dest), identity, AirPlayPasswords(securePrefs))
        }
    }

    /**
     * [dest] as an AirPlay 1 destination. Its `_airplay` TXT has no RAOP fields, so it gets
     * what AirPlay 2 receivers support: unencrypted audio and all now-playing info.
     */
    private fun raopFallback(dest: CastDestination) =
        dest.copy(extra = ExtraFields.join(listOf("et" to "0,1", "md" to "0,1,2")))

    /**
     * One connection. This is the session's failure boundary: [AirPlay2Client] surfaces
     * I/O, pairing-crypto and protocol failures as arbitrary exception types, and none of
     * them may crash the service.
     */
    @Suppress("TooGenericExceptionCaught") // Session failure boundary, see KDoc.
    private suspend fun connectAndStream(dest: CastDestination): SessionEnd {
        var streaming = false
        return try {
            val client = connect(dest)
            if (client == null) {
                SessionEnd.FAILED
            } else {
                streaming = true
                stream(dest, client)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PtpUnavailableException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "AirPlay 2 session failed for ${dest.name}: ${e.reason}")
            if (streaming) SessionEnd.LOST else SessionEnd.FAILED
        }
    }

    /** Connects, re-trying with a PIN the user entered when the receiver asks for one. */
    private suspend fun connect(dest: CastDestination): AirPlay2Client? {
        val txtPk = parseTxtPk(dest.extra)
        // Long-term HomeKit pairing identities, keyed by the receiver's AirPlay DeviceID, so
        // pair-verify can reuse the key pair pair-setup registered instead of re-pairing.
        val credentialStore = AndroidCredentialStore(securePrefs)
        // PINs are one-time codes and pairing stores long-term credentials, so a PIN that
        // older versions saved is useless.
        securePrefs.edit { remove(LEGACY_PIN_PREFIX + dest.host) }
        var retry: ConnectStep.Retry? = ConnectStep.Retry(pin = null)
        var outcome: ConnectStep = ConnectStep.Failed
        while (retry != null) {
            outcome = attempt(dest, txtPk, credentialStore, retry)
            retry = outcome as? ConnectStep.Retry
        }
        val connected = outcome as? ConnectStep.Connected
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
        if (!connected) client.close()
        return if (connected) ConnectStep.Connected(client) else ConnectStep.Failed
    }

    private suspend fun promptForPin(host: String): ConnectStep {
        val pin = ctx.pinPrompt.request(host)
        return if (pin == null) {
            ConnectStep.Failed
        } else {
            ConnectStep.Retry(pin)
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
                clientName = clientName,
                initialVolumeDb = ctx.airPlayVolumeDb,
                latencySamples = latencySamples()
            )
        )
        return client
    }

    /** Streams to the connected [client] until cancelled or the receiver is lost. */
    private suspend fun stream(dest: CastDestination, client: AirPlay2Client): SessionEnd {
        // client.connect() blocks and can't observe cancellation, so Stop or a new cast may
        // have cancelled this session while it was connecting. stop() has already set OFF
        // and cleared the registry; marking CASTING or registering now would undo that.
        if (!currentCoroutineContext().isActive) {
            withContext(NonCancellable) {
                client.teardown()
                client.close()
            }
            return SessionEnd.DONE
        }
        val lost = CompletableDeferred<String>()
        client.eventListener = object : AirPlay2Client.EventListener {
            override fun onRemoteCommand(command: String) {
                val cmd = mediaCommandFor(command) ?: return
                ctx.scope.launch { ctx.controlCommands.emit(cmd) }
            }

            override fun onVolumeChange(db: Double) {
                onReceiverVolume(db)
            }

            override fun onDisconnected(reason: String) {
                lost.complete(reason)
            }
        }
        ctx.markCasting()
        ctx.airPlay2.register(dest.host, client)
        ctx.metadata.value?.let {
            client.sendMetadata(it.title, it.artist, it.album, it.durationMs, ctx.artworkBytes)
        }
        val framer = PcmFramer(ALAC_FRAME_BYTES) { frame -> client.sendAudioFrame(frame) }
        try {
            coroutineScope {
                val audio = launch { ctx.audio.collect { framer.push(it) } }
                Log.w(LOG_TAG, "AirPlay 2 ${dest.host} lost: ${lost.await()}")
                audio.cancel()
            }
            return SessionEnd.LOST
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

    private fun latencySamples(): Int =
        if (prefs.getBoolean(AudioCastService.KEY_AIRPLAY2_STABLE_STREAMING, false)) {
            AIRPLAY2_STABLE_LATENCY_SAMPLES
        } else {
            AIRPLAY2_LOW_LATENCY_SAMPLES
        }
}

/** Secure-prefs key prefix of PINs saved by older versions. */
private const val LEGACY_PIN_PREFIX = "airplay2_pin_"
