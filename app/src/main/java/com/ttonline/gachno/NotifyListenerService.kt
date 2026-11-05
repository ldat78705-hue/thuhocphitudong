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
 * Core notification listener service.
 *
 * Key reliability mechanisms:
 * 1. onListenerDisconnected() → requestRebind() with CORRECT ComponentName
 * 2. WakeLock during notification processing to prevent CPU sleep
 * 3. WorkManager for webhook delivery (survives process death)
 * 4. SNAPSHOT content immediately to survive notification replacement
 * 5. ASYNC processing — never block Android binder threads
 * 6. EXTRA_BIG_TEXT extraction for full bank notification content
 * 7. tickerText fallback
 *
 * v2.1.5 FIX — comprehensive fix for lost transactions:
 *
 * BUG 1 (CRITICAL): synchronized(this) blocked binder threads.
 *   Android has ~16 binder threads. When 3 transactions arrive at 07:39-07:40,
 *   synchronized caused thread 2+3 to WAIT while thread 1 processed. If the wait
 *   exceeded ~5s, Android DROPPED the callback → transaction lost.
 *   FIX: Process each snapshot on a NEW Thread. ConcurrentHashMap dedup is already
 *   thread-safe. addLog uses its own logLock. No synchronized needed.
 *
 * BUG 2 (CRITICAL): extractTransactionId() returned null for "CUSTOMER HOC PHI HS193".
 *   MB Bank HOC PHI format has NO FT/ACSP code → dedup fell back to fingerprint.
 *   If InboxStyle truncated 2 lines identically → false positive duplicate → lost.
 *   FIX: Added regex HS\d{3,} to extract HS code as transactionId.
 *
 * BUG 3 (MEDIUM): WakeLock acquired AFTER snapshot but BEFORE synchronized.
 *   If thread waited on sync lock, WakeLock 30s timer ran out before processing.
 *   FIX: WakeLock acquired inside async thread, right before processing.
 *
 * v2.1.4 FIX (retained): Snapshot pattern to survive notification replacement.
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
        private val REGEX_MGD = Regex("(?:Ma GD|MGD|Ma giao dich)[:\\s]*([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
        private val REGEX_VPBFT = Regex("VPBFT(\\d{5,})")                  // VPBank
        private val REGEX_GD = Regex("(?:So GD|GD)[:\\s]+(\\d{6,})")       // BIDV
        private val REGEX_HS = Regex("HS(\\d{3,})")                        // HOC PHI HS193, HS184 — v2.1.5
        // Amount extraction — 3 patterns ordered by precision:
        // Pattern A (best):     "+400,000 VND" — explicit received amount with currency
        // Pattern B (good):     "400,000 VND" — amount with currency, may match SD
        // Pattern C (fallback): "+400,000" or "+400.000" — amount without currency suffix
        // Using A→B→C priority prevents matching SD or unrelated numbers
        private val REGEX_AMOUNT_PLUS = Regex("\\+(\\d[\\d.,]*)\\s*(?:VND|đ|dong)", RegexOption.IGNORE_CASE)
        private val REGEX_AMOUNT_ANY = Regex("(\\d{4,}[\\d.,]*)\\s*(?:VND|đ|dong)", RegexOption.IGNORE_CASE)
        private val REGEX_AMOUNT_RAW = Regex("\\+(\\d[\\d.,]{2,})")  // +400,000 without VND
    }

    /**
     * Immutable snapshot of notification content.
     * Captured IMMEDIATELY in onNotificationPosted() BEFORE any processing,
     * so that MB Bank replacing the notification won't mutate our data.
     */
    private data class NotifSnapshot(
        val packageName: String,
        val key: String,
        val id: Int,
        val tag: String?,
        val postTime: Long,
        val title: String,
        val text: String,
        val bigText: String,
        val textLines: List<String>,
        val tickerText: String?,
        val flags: Int
    )

    @Volatile
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
     */
    override fun onListenerDisconnected() {
        isRunning = false
        Log.w(TAG, "=== Listener DISCONNECTED - requesting rebind ===")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, NotifyListenerService::class.java))
            Log.d(TAG, "requestRebind() called for NotifyListenerService")
        }
    }

    /**
     * Called by Android when a notification is posted.
     *
     * CRITICAL DESIGN (v2.1.5):
     * 1. SNAPSHOT content immediately (immutable copy)
     * 2. LOG the snapshot (fast, non-blocking)
     * 3. Process on ASYNC thread — NEVER block the binder thread
     *
     * WHY no synchronized:
     * - Android binder thread pool has ~16 threads
     * - If we block with synchronized, 3 rapid transactions exhaust the pool
     * - Android DROPS callbacks when pool is exhausted → transactions LOST
     * - ConcurrentHashMap dedup is already thread-safe
     * - addLog() uses its own logLock
     * - WorkManager.enqueue() is thread-safe
     */
    @SuppressLint("DiscouragedPrivateApi")
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // ============================================================
        // STEP 1: SNAPSHOT content IMMEDIATELY on binder thread.
        // This is FAST (just reading Bundle fields) — no blocking.
        // MB Bank may replace the notification 1ms later, but our
        // snapshot is already captured as an immutable data class.
        // ============================================================
        val snapshot = try {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return
            NotifSnapshot(
                packageName = sbn.packageName ?: return,
                key = sbn.key ?: "",
                id = sbn.id,
                tag = sbn.tag,
                postTime = sbn.postTime,
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "",
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "",
                bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: "",
                textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    ?.mapNotNull { it?.toString() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList(),
                tickerText = notification.tickerText?.toString(),
                flags = notification.flags
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating snapshot: ${e.message}", e)
            return
        }

        // Detailed log on binder thread (fast, non-blocking)
        Log.d(TAG, ">>> NOTIF from: ${snapshot.packageName} " +
                "key=${snapshot.key} id=${snapshot.id} tag=${snapshot.tag} " +
                "postTime=${snapshot.postTime} " +
                "flags=0x${snapshot.flags.toString(16)} " +
                "textLines=${snapshot.textLines.size} " +
                "title='${snapshot.title.take(40)}' " +
                "text='${snapshot.text.take(60)}' " +
                "bigText='${snapshot.bigText.take(60)}'")

        // ============================================================
        // STEP 2: Process on ASYNC thread — free binder thread IMMEDIATELY.
        // This ensures Android can deliver the next notification callback
        // without waiting for our processing to finish.
        // WakeLock is acquired INSIDE the async thread to prevent CPU sleep.
        // ============================================================
        Thread {
            var wakeLock: PowerManager.WakeLock? = null
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "GachNo:NotifProcessing:${snapshot.id}"
                )
                wakeLock.acquire(30_000) // 30 second max

                processSnapshot(snapshot)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing notification: ${e.message}", e)
            } finally {
                try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
            }
        }.start()
    }

    /**
     * Process an immutable notification snapshot.
     * Called on a background thread — safe to do slow operations.
     * No synchronized needed — all shared state is already thread-safe:
     * - dedupCache: ConcurrentHashMap
     * - addLog: uses logLock
     * - WorkManager.enqueue: thread-safe
     */
    private fun processSnapshot(snap: NotifSnapshot) {
        val packageName = snap.packageName

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

        // === Determine the best content text ===
        var text = snap.text

        // Try BIG_TEXT - crucial for bank notifications!
        if (snap.bigText.isNotEmpty()) {
            text = snap.bigText
        }

        // === EXTRA_TEXT_LINES: InboxStyle support ===
        // MB Bank groups multiple transactions into a single InboxStyle notification.
        // When 2+ GDs arrive close together (e.g. QUACH THI HANG pays HS193 + HS184),
        // Android may send ONE InboxStyle notification containing ALL lines.
        // We process ALL lines individually and use isDuplicate to skip already-forwarded ones.
        if (snap.textLines.isNotEmpty()) {
            Log.d(TAG, ">>> InboxStyle detected: ${snap.textLines.size} lines, processing ALL individually")
            for (i in snap.textLines.indices) {
                val line = snap.textLines[i]
                Log.d(TAG, ">>> InboxStyle line[$i]: '${line.take(80)}'")
                forwardFromSnapshot(snap, packageName, snap.title, line, webhookUrl)
            }
            // ALSO forward the main text/bigText — isDuplicate() will naturally skip it
            // if it matches any InboxStyle line already forwarded above.
            // This is safer than heuristic startsWith(take(20)) which could false-positive
            // skip a genuine new transaction when names overlap.
            if (text.isNotEmpty()) {
                forwardFromSnapshot(snap, packageName, snap.title, text, webhookUrl)
            }
            return
        }

        // Fallback to tickerText
        if (text.isEmpty() && snap.tickerText != null) {
            text = snap.tickerText
        }

        // Skip empty
        if (snap.title.isEmpty() && text.isEmpty()) {
            Log.d(TAG, "<<< SKIP: empty title+text")
            return
        }

        // Forward single transaction (normal flow)
        forwardFromSnapshot(snap, packageName, snap.title, text, webhookUrl)
    }

    /**
     * Forward a single transaction to webhook, using snapshot data.
     * Used by both normal notifications AND each InboxStyle line.
     * isDuplicate() prevents sending the same content twice.
     *
     * Thread-safe: ConcurrentHashMap dedup + logLock for addLog.
     */
    private fun forwardFromSnapshot(
        snap: NotifSnapshot,
        packageName: String,
        title: String,
        content: String,
        webhookUrl: String
    ) {
        // Extract transaction ID for precise dedup
        // Pass title + content because MB Bank puts amount in title ("+400,000 VND")
        // while HS code is in content ("CUSTOMER HOC PHI HS193. TU: QUACH THI HANG")
        val fullText = "$title $content"
        val transactionId = extractTransactionId(fullText)
        if (transactionId != null) {
            Log.d(TAG, ">>> TransactionId extracted: $transactionId")
        }

        // Duplicate check - uses transactionId when available, falls back to full string
        if (settings.isDuplicate(packageName, title, content, snap.key, snap.postTime, transactionId)) {
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

        // Truncate content to prevent exceeding WorkManager 10KB data limit
        val safeContent = if (content.length > 4000) content.take(4000) else content
        val safeTemplate = settings.webhookParams.let {
            if (it.length > 2000) it.take(2000) else it
        }

        val workRequest = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    SendWorker.KEY_WEBHOOK_URL to webhookUrl,
                    SendWorker.KEY_APP_NAME to appName,
                    SendWorker.KEY_PACKAGE_NAME to packageName,
                    SendWorker.KEY_TITLE to title,
                    SendWorker.KEY_CONTENT to safeContent,
                    SendWorker.KEY_DEVICE_NAME to settings.deviceName,
                    SendWorker.KEY_PARAMS_TEMPLATE to safeTemplate,
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
     * Used for precise dedup — same ID = same transaction regardless of text truncation.
     *
     * v2.1.5: Added HS code extraction for HOC PHI format (Pattern 6).
     * This is CRITICAL because MB Bank's "CUSTOMER HOC PHI HS193" format
     * does NOT contain FT/ACSP codes. Without this, dedup falls back to
     * fingerprint matching which can produce false positives when InboxStyle
     * truncates two lines to identical prefixes.
     *
     * Supported patterns (checked in order):
     * 1. FT + digits     — MB Bank, Techcombank, ACB
     * 2. ACSP/ + code    — MB Bank alternate
     * 3. Ma GD + code    — VCB, Sacombank, TPBank
     * 4. VPBFT + digits  — VPBank
     * 5. GD: + digits    — BIDV
     * 6. HS + digits     — HOC PHI (gạch nợ hệ thống) ← NEW v2.1.5
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
        val vpbMatch = REGEX_VPBFT.find(text)
        if (vpbMatch != null) return "VPBFT${vpbMatch.groupValues[1]}"

        // Pattern 5: BIDV "GD: 12345678" or "So GD: 12345678"
        val gdMatch = REGEX_GD.find(text)
        if (gdMatch != null) return "GD_${gdMatch.groupValues[1]}"

        // Pattern 6: HOC PHI HS code — "CUSTOMER HOC PHI HS193" or "HOC PHI HS184"
        // CRITICAL for gạch nợ: this is the ONLY unique identifier in the MB Bank
        // HOC PHI notification format (no FT/ACSP code present).
        //
        // Combine HS code + amount to create a unique ID. This handles the case where
        // 2 different people pay for the SAME HS code (e.g. father pays 200K, mother pays 300K).
        // Without amount: both → "HS_193" → dedup drops the second one → LOST.
        // With amount: "HS_193_200000" ≠ "HS_193_300000" → both forwarded.
        //
        // Edge case: same person pays same HS same amount twice → dedup correctly blocks.
        // This is desired behavior: true duplicate from Android re-posting the notification.
        val hsMatch = REGEX_HS.find(text)
        if (hsMatch != null) {
            val hsCode = hsMatch.groupValues[1]
            // Extract amount: A→B→C priority chain
            // A: "+400,000 VND" (explicit, best)
            // B: "400,000 VND" (may match SD, but better than nothing)
            // C: "+400,000" (no currency suffix, e.g. title "+400,000")
            val amountMatch = REGEX_AMOUNT_PLUS.find(text)
                ?: REGEX_AMOUNT_ANY.find(text)
                ?: REGEX_AMOUNT_RAW.find(text)
            val amount = amountMatch?.groupValues?.get(1)?.replace(Regex("[.,]"), "") ?: ""
            return if (amount.isNotEmpty()) "HS_${hsCode}_$amount" else "HS_$hsCode"
        }

        return null
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op
    }
}
