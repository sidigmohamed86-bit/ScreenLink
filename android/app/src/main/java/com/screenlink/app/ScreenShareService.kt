package com.screenlink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ResultReceiver
import androidx.core.app.NotificationCompat

class ScreenShareService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RESULT_RECEIVER = "result_receiver"

        const val RESULT_READY = 1
        const val RESULT_FAILED = 0

        private const val CHANNEL_ID = "screenlink_share"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ScreenLink screen sharing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while ScreenLink is sharing your screen"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val receiver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_RECEIVER)
        }

        return try {
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ScreenLink")
                .setContentText("Screen sharing is active")
                .setSmallIcon(com.screenlink.app.R.drawable.ic_screenlink_notification)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            receiver?.send(RESULT_READY, null)
            START_NOT_STICKY
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            receiver?.send(
                RESULT_FAILED,
                android.os.Bundle().apply { putString("error", message) }
            )
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
