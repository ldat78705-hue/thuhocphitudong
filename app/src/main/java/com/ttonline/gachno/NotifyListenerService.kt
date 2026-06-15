package com.ttonline.gachno

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Core notification listener service - matches SmsForwarder's NotificationService exactly.
 *
 * Key reliability mechanisms:
 * 1. onListenerDisconnected() → requestRebind() with CORRECT ComponentName
 * 2. WakeLock during notification processing to prevent CPU sleep
 * 3. WorkManager for webhook delivery (survives process death)
 * 4. EXTRA_BIG_TEXT extraction for full bank notification content
 * 5. tickerText fallback
 */
@Suppress("DEPRECATION")
class NotifyListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "GachNo"
        var isRunning = false
            private set
    }

    private lateinit var settings: SettingsManager

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        isRunning = true
        Log.d(TAG, "=== NotifyListenerService CREATED ===")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.d(TAG, "=== NotifyListenerService DESTROYED ===")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isRunning = true
        Log.d(TAG, "=== Listener CONNECTED ===")
    }

    /**
     * CRITICAL: Auto-reconnect when listener disconnects.
     * Uses NotifyListenerService::class.java (NOT base class!)
     * This is what keeps the listener alive 24/7.
     */
    override fun onListenerDisconnected() {
        isRunning = false
        Log.w(TAG, "=== Listener DISCONNECTED - requesting rebind ===")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // MUST use our own class, not the base NotificationListenerService
            requestRebind(ComponentName(this, NotifyListenerService::class.java))
            Log.d(TAG, "requestRebind() called for NotifyListenerService")
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Acquire WakeLock to prevent CPU sleep during processing
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GachNo:NotificationProcessing"
        )
        wakeLock.acquire(30_000) // 30 second max

        try {
            processNotification(sbn)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification: ${e.message}", e)
        } finally {
            try { wakeLock.release() } catch (_: Exception) {}
        }
    }

    private fun processNotification(sbn: StatusBarNotification?) {
        // Skip null
        val notification = sbn?.notification ?: return
        val extras = notification.extras ?: return
        val packageName = sbn.packageName ?: return

        // Log EVERY notification
        Log.d(TAG, ">>> NOTIF from: $packageName")

        // Check forwarding enabled
        if (!settings.isForwardingEnabled) {
            Log.d(TAG, "<<< SKIP: forwarding OFF")
            return
        }

        // Check webhook URL
        val webhookUrl = settings.webhookUrl
        if (webhookUrl.isBlank()) {
            Log.d(TAG, "<<< SKIP: no webhook URL")
            return
        }

        // Skip self
        if (packageName == this.packageName) return

        // Skip system
        if (packageName == "android" || packageName == "com.android.systemui") return

        // Check app filter
        if (!settings.isAppSelected(packageName)) {
            Log.d(TAG, "<<< SKIP: not in filter: $packageName")
            return
        }

        // === Extract title ===
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""

        // === Extract text (same logic as SmsForwarder) ===
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Try BIG_TEXT - crucial for bank notifications!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            if (bigText.isNotEmpty()) {
                text = bigText
            }
        }

        // Fallback to tickerText
        if (text.isEmpty() && notification.tickerText != null) {
            text = notification.tickerText.toString()
        }

        // Skip empty
        if (title.isEmpty() && text.isEmpty()) {
            Log.d(TAG, "<<< SKIP: empty title+text")
            return
        }

        // Duplicate check
        if (settings.isDuplicate(packageName, title, text)) {
            Log.d(TAG, "<<< SKIP: duplicate")
            return
        }

        // Get app name
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        Log.d(TAG, ">>> FORWARDING: $appName [$packageName]: $title - ${text.take(100)}")

        // Save log
        val logEntry = LogEntry(
            appName = appName,
            packageName = packageName,
            title = title,
            content = text
        )
        settings.addLog(logEntry)

        // Serialize headers for WorkManager
        val headersString = settings.getHeadersMap().entries.joinToString("|||") { "${it.key}:::${it.value}" }

        // === Use WorkManager for GUARANTEED delivery ===
        // WorkManager survives process death - Android will execute even if app is killed
        val workRequest = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(
                workDataOf(
                    SendWorker.KEY_WEBHOOK_URL to webhookUrl,
                    SendWorker.KEY_APP_NAME to appName,
                    SendWorker.KEY_PACKAGE_NAME to packageName,
                    SendWorker.KEY_TITLE to title,
                    SendWorker.KEY_CONTENT to text,
                    SendWorker.KEY_DEVICE_NAME to settings.deviceName,
                    SendWorker.KEY_PARAMS_TEMPLATE to settings.webhookParams,
                    SendWorker.KEY_HEADERS_JSON to headersString,
                    SendWorker.KEY_LOG_ID to logEntry.id,
                    SendWorker.KEY_TIMEOUT to settings.requestTimeout.toLong(),
                    SendWorker.KEY_MAX_RETRIES to settings.retryTimes
                )
            )
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                15, // 15 seconds between retries
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueue(workRequest)
        Log.d(TAG, ">>> WorkManager enqueued for: $appName")

        // Notify UI
        try {
            val updateIntent = Intent("com.ttonline.gachno.LOG_UPDATED")
            sendBroadcast(updateIntent)
        } catch (_: Exception) {}
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op
    }
}
