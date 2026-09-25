package com.screenlink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class ScreenShareService : Service() {

    companion object {
        var onReady: (() -> Unit)? = null
    }

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

        ServiceCompat.startForeground(
            this,
            1001,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        // Give Android a moment to register the foreground-service type
        // before WebRTC requests the MediaProjection.
        Handler(Looper.getMainLooper()).postDelayed({
            onReady?.invoke()
            onReady = null
        }, 300)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        onReady = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
