package com.ttonline.gachno

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts ForegroundService on boot UNCONDITIONALLY.
 * Don't check isForwardingEnabled here because:
 * 1. Device-protected storage might not have migrated settings
 * 2. It's better to start and let the service check later
 * 3. If user installed app and enabled forwarding, we must honor it
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GachNo_Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "=== BootReceiver: $action ===")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT" -> {
                // ALWAYS start ForegroundService on boot
                // The service itself will check if forwarding is enabled
                Log.d(TAG, "Starting ForegroundService from boot")
                try {
                    ForegroundService.start(context)
                    Log.d(TAG, "ForegroundService.start() called successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start ForegroundService: ${e.message}", e)
                }

                // Also schedule periodic keepalive
                try {
                    KeepAliveWorker.schedule(context)
                    Log.d(TAG, "KeepAliveWorker scheduled")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to schedule KeepAliveWorker: ${e.message}", e)
                }
            }
        }
    }
}
