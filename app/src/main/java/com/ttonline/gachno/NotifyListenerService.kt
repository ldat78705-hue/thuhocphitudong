package com.ttonline.gachno

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Core service that listens for all device notifications.
 *
 * Based on the proven approach from SmsForwarder's NotificationService.kt:
 * - Uses requestRebind() on disconnection for auto-reconnect (Android N+)
 * - Extracts EXTRA_TITLE, EXTRA_TEXT, EXTRA_BIG_TEXT (for expanded bank notifications)
 * - Falls back to tickerText when text is empty
 * - Wraps everything in try-catch for stability
 * - Filters duplicate notifications within configurable interval
 * - Uses WakeLock during webhook send for reliability
 */
@Suppress("DEPRECATION")
class NotifyListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "GachNo_Listener"
        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var settings: SettingsManager
    private val webhookSender = WebhookSender()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        isRunning = true
        Log.d(TAG, "NotifyListenerService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.d(TAG, "NotifyListenerService destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isRunning = true
        Log.d(TAG, "Notification listener connected")
    }

    /**
     * Auto-reconnect when listener disconnects.
     * Mirrors SmsForwarder's approach: requestRebind() on Android N+
     */
    override fun onListenerDisconnected() {
        isRunning = false
        Log.d(TAG, "Notification listener disconnected - requesting rebind")

        // Only attempt rebind when forwarding is enabled
        if (!settings.isForwardingEnabled) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, NotificationListenerService::class.java))
        }
    }

    /**
     * Called when a notification is posted.
     * Follows the same extraction logic as SmsForwarder's NotificationService:
     * 1. Get title from EXTRA_TITLE
     * 2. Get text from EXTRA_TEXT
     * 3. Try EXTRA_BIG_TEXT (expanded notifications - crucial for bank messages)
     * 4. Fallback to tickerText
     * 5. Skip if both title and text are empty
     * 6. Check for duplicate notifications
     */
    @SuppressLint("DiscouragedPrivateApi")
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            // Skip null notifications
            val notification = sbn?.notification ?: return
            val extras = notification.extras ?: return

            // Check if forwarding is enabled
            if (!settings.isForwardingEnabled) return

            // Check webhook URL
            val webhookUrl = settings.webhookUrl
            if (webhookUrl.isBlank()) return

            val packageName = sbn.packageName ?: return

            // Skip our own notifications
            if (packageName == this.packageName) return

            // Skip system notifications (same as original SmsForwarder)
            if (packageName == "android" || packageName == "com.android.systemui") return

            // Check if this app is in the filter list
            if (!settings.isAppSelected(packageName)) return

            // === Extract notification data (matching SmsForwarder's logic exactly) ===

            // Title
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""

            // Text content - try multiple sources like SmsForwarder does
            var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

            // Try BIG_TEXT for expanded notifications (important for bank messages!)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
                if (bigText.isNotEmpty()) {
                    text = bigText
                }
            }

            // Fallback to tickerText (same as SmsForwarder)
            if (text.isEmpty() && notification.tickerText != null) {
                text = notification.tickerText.toString()
            }

            // Skip empty notifications (same as SmsForwarder)
            if (title.isEmpty() && text.isEmpty()) return

            // === Duplicate detection (like SmsForwarder's duplicateMessagesLimits) ===
            if (settings.isDuplicate(packageName, title, text)) {
                Log.d(TAG, "Duplicate notification skipped: $packageName - $title")
                return
            }

            // Get app name
            val appName = try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }

            Log.d(TAG, "Notification from $appName [$packageName]: $title - $text")

            // Create log entry
            val logEntry = LogEntry(
                appName = appName,
                packageName = packageName,
                title = title,
                content = text
            )
            settings.addLog(logEntry)

            // Send to webhook with WakeLock and configurable timeout/retry
            serviceScope.launch {
                try {
                    val result = webhookSender.send(
                        webhookUrl = webhookUrl,
                        appName = appName,
                        packageName = packageName,
                        title = title,
                        content = text,
                        deviceName = settings.deviceName,
                        context = this@NotifyListenerService,
                        timeoutSeconds = settings.requestTimeout.toLong(),
                        maxRetries = settings.retryTimes,
                        paramsTemplate = settings.webhookParams,
                        headers = settings.getHeadersMap()
                    )

                    if (result.success) {
                        settings.updateLog(logEntry.id, LogEntry.Status.SUCCESS, result.responseCode)
                        Log.d(TAG, "Webhook sent successfully: ${result.responseCode}")
                    } else {
                        settings.updateLog(logEntry.id, LogEntry.Status.FAILED, result.responseCode, result.errorMessage)
                        Log.e(TAG, "Webhook failed: ${result.errorMessage}")
                    }

                    // Notify MainActivity to refresh UI
                    val updateIntent = Intent("com.ttonline.gachno.LOG_UPDATED")
                    sendBroadcast(updateIntent)

                } catch (e: Exception) {
                    settings.updateLog(logEntry.id, LogEntry.Status.FAILED, 0, e.message ?: "Unknown")
                    Log.e(TAG, "Error sending webhook", e)
                }
            }

        } catch (e: Exception) {
            // Catch all exceptions to prevent service crash (same pattern as SmsForwarder)
            Log.e(TAG, "Parsing Notification failed: " + e.message.toString())
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op, just log for debugging
    }
}
