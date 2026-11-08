package com.ttonline.gachno

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Watchdog that uses AlarmManager exact alarms to check NotifyListenerService health
 * every 5 minutes. This is MORE RELIABLE than WorkManager's 15-minute minimum:
 *
 * - setExactAndAllowWhileIdle() fires even in Doze mode
 * - 5-minute interval = max 5 min of missed notifications after service kill
 * - Self-rescheduling: each alarm fires → checks → schedules next alarm
 *
 * Flow: Alarm fires → onReceive() → check isRunning → if dead → restart FG → schedule next
 *
 * WHY AlarmManager over WorkManager:
 * - WorkManager minimum interval = 15 minutes
 * - AlarmManager exact alarms can fire every 5 minutes
 * - setExactAndAllowWhileIdle has 9-minute minimum on Android 6+, but we schedule 5 min
 *   and Android will delay to ~9 min at worst — still better than 15 min
 */
class ServiceWatchdog : BroadcastReceiver() {

    companion object {
        private const val TAG = "GachNo_Watchdog"
        private const val ACTION_WATCHDOG = "com.ttonline.gachno.WATCHDOG_CHECK"
        private const val INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val REQUEST_CODE = 9999

        /**
         * Schedule the next watchdog alarm.
         * Safe to call multiple times — PendingIntent with same requestCode replaces previous.
         */
        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ServiceWatchdog::class.java).apply {
                action = ACTION_WATCHDOG
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)

            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pi
                    )
                } else {
                    am.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pi
                    )
                }
                Log.d(TAG, "Watchdog alarm scheduled in ${INTERVAL_MS / 1000}s")
            } catch (e: SecurityException) {
                // Android 12+ may deny exact alarms if permission not granted
                // Fall back to inexact alarm
                Log.w(TAG, "Exact alarm denied, using inexact: ${e.message}")
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }

        /**
         * Cancel the watchdog alarm.
         */
        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ServiceWatchdog::class.java).apply {
                action = ACTION_WATCHDOG
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
            am.cancel(pi)
            Log.d(TAG, "Watchdog alarm cancelled")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCHDOG) return

        Log.d(TAG, "=== Watchdog alarm fired ===")

        try {
            val settings = SettingsManager(context)

            if (!settings.isForwardingEnabled) {
                Log.d(TAG, "Forwarding disabled — skipping check")
                // Don't reschedule if forwarding is off
                return
            }

            val listenerAlive = NotifyListenerService.isRunning
            Log.d(TAG, "NotifyListenerService.isRunning = $listenerAlive")

            // Save health status for UI display
            settings.saveWatchdogStatus(listenerAlive)

            if (!listenerAlive) {
                Log.w(TAG, "Listener is DEAD — restarting ForegroundService to recover")
                ForegroundService.start(context)
            } else {
                Log.d(TAG, "Listener is alive")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog error: ${e.message}", e)
        }

        // ALWAYS reschedule next alarm (self-sustaining chain)
        schedule(context)
    }
}
