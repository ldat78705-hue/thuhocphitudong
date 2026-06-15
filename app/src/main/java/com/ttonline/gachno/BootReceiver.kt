package com.ttonline.gachno

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts ForegroundService on boot to keep the process alive.
 * NotificationListenerService auto-starts if permission is granted,
 * but without ForegroundService the process gets killed by Android.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GachNo_Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT" -> {
                Log.d(TAG, "Boot detected: $action")

                val settings = SettingsManager(context)
                if (settings.isForwardingEnabled) {
                    Log.d(TAG, "Forwarding ON - starting ForegroundService")
                    ForegroundService.start(context)
                } else {
                    Log.d(TAG, "Forwarding OFF - not starting service")
                }
            }
        }
    }
}
