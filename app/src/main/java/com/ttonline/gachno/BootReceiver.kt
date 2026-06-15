package com.ttonline.gachno

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Ensures the notification listener service stays active after device reboot.
 * NotificationListenerService auto-starts if permission is granted,
 * this receiver just logs the boot event for debugging.
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
                Log.d(TAG, "Device boot detected: $action")

                val settings = SettingsManager(context)
                if (settings.isForwardingEnabled) {
                    Log.d(TAG, "Forwarding enabled - NotificationListenerService will auto-start")
                } else {
                    Log.d(TAG, "Forwarding disabled - notifications will not be forwarded")
                }
            }
        }
    }
}
