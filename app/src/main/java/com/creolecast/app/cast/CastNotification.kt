package com.creolecast.app.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.creolecast.app.AudioCastService
import com.creolecast.app.CastState
import com.creolecast.app.R

private const val CHANNEL_ID = "AudioCastChannel"
private const val STOP_REQUEST_CODE = 0
private const val VOLUME_UP_REQUEST_CODE = 1
private const val VOLUME_DOWN_REQUEST_CODE = 2

/** The ongoing cast notification: status, Stop, and the receiver volume buttons and bar. */
internal class CastNotification(
    private val service: Service,
    private val ctx: CastContext,
    private val volume: ReceiverVolume
) {
    private val notificationManager =
        service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun update() {
        notificationManager.notify(ID, build())
    }

    fun build(): Notification {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                service.getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val remoteViews = RemoteViews(service.packageName, R.layout.notification_casting)
        remoteViews.setTextViewText(R.id.notification_title, service.getString(R.string.app_name))
        remoteViews.setTextViewText(R.id.notification_text, statusText())
        remoteViews.setOnClickPendingIntent(
            R.id.notification_stop_button,
            serviceAction(AudioCastService.ACTION_STOP, STOP_REQUEST_CODE)
        )
        remoteViews.setOnClickPendingIntent(
            R.id.notification_vol_up,
            serviceAction(AudioCastService.ACTION_VOLUME_UP, VOLUME_UP_REQUEST_CODE)
        )
        remoteViews.setOnClickPendingIntent(
            R.id.notification_vol_down,
            serviceAction(AudioCastService.ACTION_VOLUME_DOWN, VOLUME_DOWN_REQUEST_CODE)
        )
        remoteViews.setProgressBar(
            R.id.notification_volume_bar,
            ReceiverVolume.MAX_STEPS,
            volume.steps,
            false
        )

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_icon)
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun statusText(): String {
        val destinations = ctx.destinations.value
        return when (ctx.state.value) {
            CastState.CASTING -> {
                if (destinations.size > 1) {
                    "Casting to ${destinations.size} devices"
                } else {
                    service.getString(
                        R.string.casting_to,
                        destinations.firstOrNull()?.name ?: "Unknown",
                        ""
                    )
                }
            }

            CastState.CONNECTING -> "Connecting..."

            CastState.ERROR -> "Connection Error"

            CastState.OFF, CastState.DISCOVERING -> service.getString(R.string.not_casting)
        }
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            service,
            requestCode,
            Intent(service, AudioCastService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val ID = 1
    }
}
