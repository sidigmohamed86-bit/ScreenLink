package com.screenlink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ScreenShareService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channelId = "screenlink_share"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "ScreenLink screen sharing",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ScreenLink")
            .setContentText("Screen sharing is active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
