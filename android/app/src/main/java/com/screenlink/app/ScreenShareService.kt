package com.screenlink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ScreenShareService : Service() {
    companion object {
        var onReady: (() -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "screenlink_share",
                "ScreenLink screen sharing",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            val notification: Notification = NotificationCompat.Builder(
                this,
                "screenlink_share"
            )
                .setContentTitle("ScreenLink")
                .setContentText("Screen sharing is active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    1001,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(1001, notification)
            }

            onReady?.invoke()
            onReady = null
            START_NOT_STICKY
        } catch (e: Throwable) {
            onReady?.invoke()
            onReady = null
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        onReady = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
