package com.ttonline.gachno

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager worker that ensures ForegroundService stays alive.
 * Runs every 15 minutes (minimum WorkManager interval).
 * If ForegroundService was killed by system, this restarts it.
 * 
 * WorkManager is guaranteed by Android to execute regardless of app state.
 */
class KeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "GachNo_KeepAlive"
        private const val WORK_NAME = "gachno_keepalive"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "KeepAliveWorker scheduled (every 15 min)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "KeepAliveWorker cancelled")
        }
    }

    override fun doWork(): Result {
        Log.d(TAG, "KeepAlive check running")

        try {
            val settings = SettingsManager(applicationContext)
            if (settings.isForwardingEnabled) {
                Log.d(TAG, "Forwarding enabled - ensuring ForegroundService is running")
                ForegroundService.start(applicationContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "KeepAlive error: ${e.message}", e)
        }

        return Result.success()
    }
}
