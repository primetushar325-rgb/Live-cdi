package com.mihad.live.engine

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object Notifications {

    const val CHANNEL_STREAM = "mihad_live_stream"
    const val CHANNEL_ALERTS = "mihad_live_alerts"
    const val NOTIFICATION_ID_STREAM = 9001

    fun createChannels(app: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val stream = NotificationChannel(
            CHANNEL_STREAM,
            "Live stream",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the live stream running while the screen is off"
            setSound(null, null)
            enableVibration(false)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "Live alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Stream failures that need attention"
        }
        manager.createNotificationChannel(stream)
        manager.createNotificationChannel(alerts)
    }
}
