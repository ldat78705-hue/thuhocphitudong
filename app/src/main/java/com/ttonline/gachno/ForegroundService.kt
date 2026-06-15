package com.ttonline.gachno

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground Service that keeps GachNo process alive.
 * Without this, Android kills the process and NotificationListenerService dies.
 *
 * For Android 14 (API 34): foregroundServiceType="specialUse" is required.
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "GachNo_FG"
        private const val CHANNEL_ID = "gachno_foreground"
        private const val NOTIFY_ID = 1001

        fun start(context: Context) {
            try {
                val intent = Intent(context, ForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ForegroundService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ForegroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop ForegroundService: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()

        // Android 14+ requires foregroundServiceType in startForeground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFY_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFY_ID, notification)
        }

        Log.d(TAG, "ForegroundService started - process kept alive")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "ForegroundService destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GachNo Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.foreground_description)
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.foreground_running))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
