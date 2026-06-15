package com.ttonline.gachno

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground Service that keeps GachNo alive in the background.
 * Mirrors SmsForwarder's ForegroundService approach:
 * - Creates a persistent notification on the status bar
 * - Uses startForeground() so Android won't kill the process
 * - Toggles NotificationListenerService to force reconnection
 * - Returns START_STICKY so it restarts if killed
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "GachNo_FG"
        private const val CHANNEL_ID = "gachno_foreground"
        private const val CHANNEL_NAME = "GachNo Service"
        private const val NOTIFY_ID = 1001
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ForegroundService::class.java)
            intent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startFromBoot(context: Context) {
            val intent = Intent(context, ForegroundService::class.java)
            intent.action = ACTION_START_BOOT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ForegroundService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }

        private const val ACTION_START = "com.ttonline.gachno.START"
        private const val ACTION_START_BOOT = "com.ttonline.gachno.START_BOOT"
        private const val ACTION_STOP = "com.ttonline.gachno.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_START_BOOT -> {
                startForegroundService()
                // Delay toggle on boot to let system settle
                android.os.Handler(mainLooper).postDelayed({
                    toggleNotificationListenerService()
                }, 3000)
            }
            ACTION_STOP -> stopForegroundService()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun startForegroundService() {
        if (isRunning) return
        isRunning = true

        val notification = createNotification(
            getString(R.string.foreground_running)
        )
        startForeground(NOTIFY_ID, notification)

        Log.d(TAG, "Foreground service started")
    }

    private fun stopForegroundService() {
        try {
            isRunning = false
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground service: ${e.message}")
        }
    }

    /**
     * Toggle the NotificationListenerService to force Android to reconnect.
     * This is the key trick from SmsForwarder that ensures the listener stays active.
     * It disables then re-enables the service component.
     */
    private fun toggleNotificationListenerService() {
        try {
            val cn = ComponentName(this, NotifyListenerService::class.java)
            val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            if (flat != null && flat.contains(cn.flattenToString())) {
                val pm = packageManager
                // Disable then enable to force rebind
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.d(TAG, "NotificationListenerService toggled for reconnection")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling listener: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Low = no sound, but visible
            ).apply {
                description = getString(R.string.foreground_description)
                enableLights(true)
                lightColor = Color.GREEN
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= 30) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(System.currentTimeMillis())
            .build()
    }

    fun updateNotification(content: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notification = createNotification(content)
            nm.notify(NOTIFY_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}")
        }
    }
}
