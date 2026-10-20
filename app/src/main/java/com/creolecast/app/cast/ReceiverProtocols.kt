package com.creolecast.app.cast

import android.content.Context
import android.content.SharedPreferences
import com.creolecast.app.AudioCastService
import com.creolecast.app.CastDestination
import com.creolecast.app.CastPlatform
import com.creolecast.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Starts and stops the protocol session(s) for a destination, by its discovered platform. */
internal class ReceiverProtocols(
    private val ctx: CastContext,
    private val identity: AirPlayIdentity,
    securePrefs: SharedPreferences,
    context: Context,
    volume: ReceiverVolume
) {
    private val passwords = AirPlayPasswords(securePrefs)
    val dlna = DlnaController(ctx)
    val googleCast = GoogleCastController(ctx, volume)
    private val airPlay2 = AirPlay2Session(
        ctx,
        identity,
        securePrefs,
        context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE),
        context.getString(R.string.app_name),
        volume::reflectReceiverDb
    )

    /** Launches the session(s) streaming to [dest] as children of the cast's [scope]. */
    fun launch(scope: CoroutineScope, dest: CastDestination) {
        when (dest.platform) {
            CastPlatform.DLNA -> scope.launch { dlna.start(dest) }

            CastPlatform.GOOGLE_CAST -> scope.launch { googleCast.cast(dest) }

            CastPlatform.AIRPLAY -> scope.launch {
                RaopSession.cast(ctx, dest, identity, passwords)
            }

            CastPlatform.AIRPLAY2 -> scope.launch { airPlay2.cast(dest) }

            CastPlatform.SNAPCAST -> scope.launch { SnapcastSession(ctx, dest).run() }

            CastPlatform.ARIACAST, CastPlatform.MANUAL -> {
                val session = AriaCastSession(ctx, dest)
                scope.launch { session.runControl() }
                scope.launch { session.runAudio() }
                scope.launch { session.runStats() }
            }
        }
    }

    /** Asks each receiver of the ending cast to stop, in parallel; the jobs end when sent. */
    fun stopAll(destinations: List<CastDestination>): List<Job> = destinations.map { dest ->
        ctx.scope.launch { bestEffort("Stopping ${dest.name}") { stop(dest) } }
    }

    private suspend fun stop(dest: CastDestination) {
        when (dest.platform) {
            CastPlatform.AIRPLAY2 -> {
                ctx.airPlay2[dest.host]?.teardown()
                ctx.raop.teardown(dest) // the AirPlay 1 fallback, if it was used
            }

            CastPlatform.DLNA -> dlna.stop(dest)

            CastPlatform.GOOGLE_CAST -> ctx.googleCast[dest.host]?.stop()

            CastPlatform.AIRPLAY -> ctx.raop.teardown(dest)

            CastPlatform.SNAPCAST, CastPlatform.ARIACAST, CastPlatform.MANUAL -> Unit
        }
    }
}
