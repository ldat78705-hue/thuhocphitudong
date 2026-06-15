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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground Service that:
 * 1. Keeps GachNo process alive (prevents Android from killing it)
 * 2. Toggles NotificationListenerService to force reconnection (delayed 3s)
 *
 * This is the KEY mechanism from SmsForwarder that makes it work reliably.
 * Without ForegroundService, Android kills the process → listener dies.
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
                Log.e(TAG, "Failed to start: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ForegroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop: ${e.message}")
            }
        }
    }

    private var hasToggled = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()

        // Android 14+ requires foregroundServiceType in startForeground()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFY_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFY_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
            // Fallback: just show notification
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFY_ID, notification)
        }

        Log.d(TAG, "ForegroundService started")

        // Toggle listener ONCE after 3 second delay
        // This forces Android to rebind NotificationListenerService
        // Same approach as SmsForwarder's CommonUtils.toggleNotificationListenerService()
        if (!hasToggled) {
            hasToggled = true
            Handler(Looper.getMainLooper()).postDelayed({
                toggleNotificationListenerService()
            }, 3000)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hasToggled = false
        Log.d(TAG, "ForegroundService destroyed")
        super.onDestroy()
    }

    /**
     * Toggle the NotificationListenerService component to force Android rebind.
     * This is THE critical trick from SmsForwarder:
     * - Disable component → Android unbinds listener
     * - Enable component → Android rebinds listener
     * - DONT_KILL_APP prevents process death
     * - 3 second delay ensures the activity is already settled
     */
    private fun toggleNotificationListenerService() {
        try {
            val cn = ComponentName(this, NotifyListenerService::class.java)
            val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            
            Log.d(TAG, "enabled_notification_listeners: $flat")
            
            if (flat != null && flat.contains(cn.flattenToString())) {
                val pm = packageManager
                
                // Step 1: Disable
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.d(TAG, "Listener component DISABLED")
                
                // Step 2: Wait 500ms then re-enable
                Handler(Looper.getMainLooper()).postDelayed({
                    pm.setComponentEnabledSetting(
                        cn,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.d(TAG, "Listener component RE-ENABLED - rebind should happen now")
                }, 500)
            } else {
                Log.w(TAG, "NotifyListenerService NOT in enabled_notification_listeners!")
                Log.w(TAG, "User needs to grant Notification Access permission")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling listener: ${e.message}")
        }
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
