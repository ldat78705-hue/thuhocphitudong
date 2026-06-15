package com.ttonline.gachno

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Ensures the notification listener service stays active after device reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("GachNo_Boot", "Device booted, notification listener will auto-start")
            // NotificationListenerService auto-starts if permission is granted
            // No explicit action needed
        }
    }
}
