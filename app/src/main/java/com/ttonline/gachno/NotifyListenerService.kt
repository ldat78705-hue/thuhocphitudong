package com.ttonline.gachno

import android.app.Notification
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
 * When a notification from a selected app arrives, it extracts the data
 * and forwards it via webhook.
 */
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

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isRunning = false
        Log.d(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Check if forwarding is enabled
        if (!settings.isForwardingEnabled) return

        // Check webhook URL
        val webhookUrl = settings.webhookUrl
        if (webhookUrl.isBlank()) return

        val packageName = sbn.packageName ?: return

        // Skip our own notifications
        if (packageName == this.packageName) return

        // Skip system notifications
        if (packageName == "android" || packageName == "com.android.systemui") return

        // Check if this app is in the filter list
        if (!settings.isAppSelected(packageName)) return

        // Extract notification data
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Try to get big text (expanded notification content - useful for bank notifications)
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val content = bigText ?: text

        // Skip empty notifications
        if (content.isBlank() && title.isBlank()) return

        // Get app name
        val appName = try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        Log.d(TAG, "Notification from $appName: $title - $content")

        // Create log entry
        val logEntry = LogEntry(
            appName = appName,
            packageName = packageName,
            title = title,
            content = content
        )
        settings.addLog(logEntry)

        // Send to webhook
        serviceScope.launch {
            try {
                val result = webhookSender.send(
                    webhookUrl = webhookUrl,
                    appName = appName,
                    packageName = packageName,
                    title = title,
                    content = content,
                    deviceName = settings.deviceName
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
    }
}
