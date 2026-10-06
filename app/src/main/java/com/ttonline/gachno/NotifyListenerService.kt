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
        @Volatile
        var isRunning = false
            private set

        // Pre-compiled regex patterns for transaction ID extraction (avoid recompilation per call)
        // Order matters: most specific first, most generic last
        private val REGEX_FT = Regex("FT\\d{5,}")                          // MB Bank, Techcombank, ACB
        private val REGEX_ACSP = Regex("ACSP/\\s*([A-Za-z0-9]+)")          // MB Bank alternate
        private val REGEX_MGD = Regex("(?:Ma GD|MGD|Ma giao dich)[:\\s]*([A-Za-z0-9]+)", RegexOption.IGNORE_CASE) // VCB, Sacombank, TPBank
        private val REGEX_VPBFT = Regex("VPBFT(\\d{5,})")                  // VPBank: VPBFT12345678
        private val REGEX_GD = Regex("(?:So GD|GD)[:\\s]+(\\d{6,})")       // BIDV: GD: 12345678
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
        var wakeLock: PowerManager.WakeLock? = null
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GachNo:NotificationProcessing"
            )
            wakeLock.acquire(30_000) // 30 second max

            // SYNCHRONIZED: Serialize notification processing to prevent race conditions
            synchronized(this) {
                processNotification(sbn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification: ${e.message}", e)
        } finally {
            try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
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
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        if (bigText.isNotEmpty()) {
            text = bigText
        }

        // === EXTRA_TEXT_LINES: InboxStyle support ===
        // MB Bank groups multiple transactions into a single InboxStyle notification.
        // When 2 GDs arrive at the SAME second (e.g. NGUYEN THI YEN pays HS093 + HS006 at 20:07),
        // Android may send ONLY ONE InboxStyle notification containing BOTH lines.
        // We must process ALL lines individually and use isDuplicate to skip already-forwarded ones.
        //
        // Flow: GD1(HS093) + GD2(HS006) arrive at 20:07 →
        //   Android sends 1 InboxStyle with textLines = ["...HS093...", "...HS006..."]
        //   We process EACH line: HS093 → new → forward ✅, HS006 → new → forward ✅
        //   If GD1 was already sent as standalone: HS093 → isDuplicate → skip ✅
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null && textLines.isNotEmpty()) {
            Log.d(TAG, ">>> InboxStyle detected: ${textLines.size} lines, processing ALL lines individually")
            for (i in textLines.indices) {
                val line = textLines[i]?.toString() ?: continue
                if (line.isEmpty()) continue
                // Forward each line as a separate transaction
                forwardTransaction(sbn, packageName, title, line, webhookUrl)
            }
            return // All lines processed individually, skip normal flow
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

        // Forward single transaction (normal flow)
        forwardTransaction(sbn, packageName, title, text, webhookUrl)
    }

    /**
     * Forward a single transaction to webhook.
     * Used by both normal notifications AND each InboxStyle line.
     * isDuplicate() prevents sending the same content twice.
     */
    private fun forwardTransaction(
        sbn: StatusBarNotification,
        packageName: String,
        title: String,
        content: String,
        webhookUrl: String
    ) {
        // Extract transaction ID (FT code) for precise dedup
        // Synced with server v2 which uses transactionId for idempotent dedup
        val transactionId = extractTransactionId(content)
        if (transactionId != null) {
            Log.d(TAG, ">>> TransactionId extracted: $transactionId")
        }

        // Duplicate check - uses transactionId when available, falls back to full string
        if (settings.isDuplicate(packageName, title, content, sbn.key, sbn.postTime, transactionId)) {
            Log.w(TAG, "<<< SKIP duplicate: pkg=$packageName " +
                    "txId=$transactionId content='${content.take(100)}...'")
            return
        }

        // Get app name
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        Log.d(TAG, ">>> FORWARDING: $appName [$packageName]: $title - ${content.take(100)}")

        // Save log
        val logEntry = LogEntry(
            appName = appName,
            packageName = packageName,
            title = title,
            content = content
        )
        settings.addLog(logEntry)

        // Serialize headers for WorkManager (use Gson for safe serialization)
        val headersString = com.google.gson.Gson().toJson(settings.getHeadersMap())

        // === Use WorkManager for GUARANTEED delivery ===
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    SendWorker.KEY_WEBHOOK_URL to webhookUrl,
                    SendWorker.KEY_APP_NAME to appName,
                    SendWorker.KEY_PACKAGE_NAME to packageName,
                    SendWorker.KEY_TITLE to title,
                    SendWorker.KEY_CONTENT to content,
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
                15,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueue(workRequest)
        Log.d(TAG, ">>> WorkManager enqueued for: $appName (txId=$transactionId)")

        // Notify UI
        try {
            val updateIntent = Intent("com.ttonline.gachno.LOG_UPDATED")
            updateIntent.setPackage(this.packageName)
            sendBroadcast(updateIntent)
        } catch (_: Exception) {}
    }

    /**
     * Extract unique transaction ID from bank notification content.
     * Used for precise dedup - same FT code = same transaction regardless of text truncation.
     *
     * Supported patterns:
     * - MB Bank: FT26279025922201 (FT + 14-17 digits)
     * - Vietcombank: CT từ ... (mã GD after "Ma GD" or "MGĐ")
     * - Techcombank: FT code in content
     * - BIDV: mã GD pattern
     * - Generic: any FT/CT followed by digits
     */
    private fun extractTransactionId(text: String): String? {
        // Pattern 1: FT + digits (MB Bank, Techcombank)
        val ftMatch = REGEX_FT.find(text)
        if (ftMatch != null) return ftMatch.value

        // Pattern 2: "ACSP/" followed by code (MB Bank format: "Ma GD ACSP/ V1083643")
        // MUST check BEFORE "Ma GD" pattern — otherwise "Ma GD" captures "ACSP" as the code
        val acspMatch = REGEX_ACSP.find(text)
        if (acspMatch != null) return "ACSP_${acspMatch.groupValues[1]}"

        // Pattern 3: "Ma GD" or "MGD" followed by alphanumeric code (non-ACSP)
        val mgdMatch = REGEX_MGD.find(text)
        if (mgdMatch != null) return mgdMatch.groupValues[1]

        // Pattern 4: VPBank "VPBFT12345678" — VPB prefix before FT
        // Only reached when REGEX_FT (pure FT\d+) didn't match
        val vpbMatch = REGEX_VPBFT.find(text)
        if (vpbMatch != null) return "VPBFT${vpbMatch.groupValues[1]}"

        // Pattern 5: BIDV "GD: 12345678" or "So GD: 12345678"
        // Requires 6+ digits to avoid false match on short numbers
        val gdMatch = REGEX_GD.find(text)
        if (gdMatch != null) return "GD_${gdMatch.groupValues[1]}"

        return null
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op
    }
}
