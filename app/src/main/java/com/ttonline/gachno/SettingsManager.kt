package com.ttonline.gachno

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages all app settings via SharedPreferences.
 * Stores webhook URL, filtered apps, forwarding state, logs, language preference,
 * duplicate filter, retry config, webhook params template, and editable test data.
 *
 * Based on SmsForwarder's SettingUtils approach but simplified.
 */
class SettingsManager(context: Context) {

    companion object {
        private const val TAG = "GachNo_Dedup"
        private const val PREFS_NAME = "gachno_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_WEBHOOK_PARAMS = "webhook_params"
        private const val KEY_WEBHOOK_HEADERS = "webhook_headers"
        private const val KEY_SELECTED_APPS = "selected_apps"
        private const val KEY_FORWARDING_ENABLED = "forwarding_enabled"
        private const val KEY_LOGS = "logs"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DUPLICATE_INTERVAL = "duplicate_interval"
        private const val KEY_RETRY_TIMES = "retry_times"
        private const val KEY_REQUEST_TIMEOUT = "request_timeout"
        // Editable test fields
        private const val KEY_TEST_APP_NAME = "test_app_name"
        private const val KEY_TEST_PACKAGE = "test_package"
        private const val KEY_TEST_TITLE = "test_title"
        private const val KEY_TEST_CONTENT = "test_content"
        // Setup wizard
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        // Last notification hash for duplicate detection
        private const val KEY_LAST_NOTIFY_HASH = "last_notify_hash"
        private const val KEY_LAST_NOTIFY_TIME = "last_notify_time"

        private const val MAX_LOGS = 200

        /**
         * Default Params template (same format as SmsForwarder).
         * Available placeholders:
         *   [title]        - notification title
         *   [content]      - notification content (full text)
         *   [app_name]     - app label (e.g. "MB Bank")
         *   [package_name] - package (e.g. "com.mbmobile")
         *   [device_name]  - device model
         *   [timestamp]    - ISO 8601 timestamp
         */
        const val DEFAULT_PARAMS = """{"text": "[title] [content]"}"""

        /**
         * Thread-safe in-memory dedup cache.
         * Key = fingerprint (packageName + content hash), Value = timestamp (ms).
         *
         * WHY ConcurrentHashMap instead of SharedPreferences:
         * - SharedPreferences apply() is ASYNC → 2 threads can read stale data and overwrite
         *   each other → entries lost → transactions lost (DAO THI XOAN HS021 case)
         * - ConcurrentHashMap is lock-free for reads, segment-locked for writes → no race condition
         * - Lives in companion object → shared across all SettingsManager instances in same process
         * - NotificationListenerService runs in main process → same JVM → same map
         */
        internal val dedupCache = ConcurrentHashMap<String, Long>(32)

        // Pre-compiled regex for Priority 2 amount dedup
        private val REGEX_AMT = Regex("\\+(\\d[\\d.,]*)\\s*(?:VND|đ|dong)", RegexOption.IGNORE_CASE)
        private val REGEX_SEP = Regex("[.,]")
    }

    private val prefs: SharedPreferences = run {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val deviceContext = context.createDeviceProtectedStorageContext()
            // Migrate from credential-encrypted storage (v1.6) to device-protected (v1.7+)
            // This is safe to call multiple times - it only copies if target doesn't exist
            deviceContext.moveSharedPreferencesFrom(context, PREFS_NAME)
            deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } else {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
    private val gson = Gson()

    // --- Webhook URL ---
    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()

    // --- Webhook Params (body template with placeholders) ---
    // Same concept as SmsForwarder's webParams field
    var webhookParams: String
        get() = prefs.getString(KEY_WEBHOOK_PARAMS, DEFAULT_PARAMS) ?: DEFAULT_PARAMS
        set(value) = prefs.edit().putString(KEY_WEBHOOK_PARAMS, value).apply()

    // --- Webhook Headers (key:value pairs, one per line) ---
    var webhookHeaders: String
        get() = prefs.getString(KEY_WEBHOOK_HEADERS, "Content-Type: application/json") ?: "Content-Type: application/json"
        set(value) = prefs.edit().putString(KEY_WEBHOOK_HEADERS, value).apply()

    // --- Device Name ---
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, android.os.Build.MODEL) ?: android.os.Build.MODEL
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    // --- Forwarding Toggle ---
    var isForwardingEnabled: Boolean
        get() = prefs.getBoolean(KEY_FORWARDING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FORWARDING_ENABLED, value).apply()

    // --- Setup Wizard ---
    var isSetupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETED, value).apply()

    // --- Language ---
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "vi") ?: "vi"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    // --- Duplicate Filter Interval (seconds, 0 = disabled) ---
    var duplicateInterval: Int
        get() {
            val v = prefs.getInt(KEY_DUPLICATE_INTERVAL, 5)
            // Migrate: old versions had 30s default which is too aggressive
            // with the new key+postTime detection, 5s is sufficient
            if (v == 30) {
                duplicateInterval = 5
                return 5
            }
            return v
        }
        set(value) = prefs.edit().putInt(KEY_DUPLICATE_INTERVAL, value).apply()

    // --- Retry Times ---
    var retryTimes: Int
        get() = prefs.getInt(KEY_RETRY_TIMES, 3)
        set(value) = prefs.edit().putInt(KEY_RETRY_TIMES, value).apply()

    // --- Request Timeout (seconds) ---
    var requestTimeout: Int
        get() = prefs.getInt(KEY_REQUEST_TIMEOUT, 15)
        set(value) = prefs.edit().putInt(KEY_REQUEST_TIMEOUT, value).apply()

    // --- Editable Test Fields (saved for reuse) ---
    var testAppName: String
        get() = prefs.getString(KEY_TEST_APP_NAME, "MB Bank (Test)") ?: "MB Bank (Test)"
        set(value) = prefs.edit().putString(KEY_TEST_APP_NAME, value).apply()

    var testPackage: String
        get() = prefs.getString(KEY_TEST_PACKAGE, "com.mbmobile") ?: "com.mbmobile"
        set(value) = prefs.edit().putString(KEY_TEST_PACKAGE, value).apply()

    var testTitle: String
        get() = prefs.getString(KEY_TEST_TITLE, "Thông báo giao dịch") ?: "Thông báo giao dịch"
        set(value) = prefs.edit().putString(KEY_TEST_TITLE, value).apply()

    var testContent: String
        get() = prefs.getString(KEY_TEST_CONTENT,
            "TK 0123456789 +500,000 VND lúc 15:30 15/06/2026. SD: 1,200,000 VND. GachNo test."
        ) ?: ""
        set(value) = prefs.edit().putString(KEY_TEST_CONTENT, value).apply()

    // --- Selected Apps (package names) ---
    fun getSelectedApps(): Set<String> {
        val json = prefs.getString(KEY_SELECTED_APPS, "[]") ?: "[]"
        val type = object : TypeToken<Set<String>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun setSelectedApps(apps: Set<String>) {
        prefs.edit().putString(KEY_SELECTED_APPS, gson.toJson(apps)).apply()
    }

    fun isAppSelected(packageName: String): Boolean {
        val selectedApps = getSelectedApps()
        return selectedApps.isEmpty() || selectedApps.contains(packageName)
    }

    // --- Duplicate Detection ---
    /**
     * Detects true duplicate notifications using ConcurrentHashMap (thread-safe).
     *
     * DEDUP STRATEGY (v2.0.3 - synced with server v2):
     *
     * Priority 1: transactionId (FT code) — when available
     *   - FT26279025922201 is UNIQUE per bank transaction
     *   - Solves InboxStyle truncation: same FT code in full text AND truncated text
     *   - Synced with server using transactionId for idempotent gạch nợ
     *
     * Priority 2: full string fingerprint — fallback for non-bank notifications
     *   - packageName + title + content
     *   - Zero collision risk
     *
     * THREAD SAFETY:
     * - ConcurrentHashMap.putIfAbsent() is ATOMIC
     * - No read-then-write gap
     */
    fun isDuplicate(packageName: String, title: String, content: String,
                    notifKey: String, postTime: Long,
                    transactionId: String? = null): Boolean {
        val interval = duplicateInterval
        if (interval <= 0) return false

        val now = System.currentTimeMillis()
        val intervalMs = interval * 1000L

        // === Cleanup expired entries first, then check size ===
        // TX entries (prefix "TX:") use 24h TTL, other entries use intervalMs
        val txTtlMs = 24 * 60 * 60 * 1000L
        val expiredKeys = dedupCache.entries
            .filter { entry ->
                val ttl = if (entry.key.startsWith("TX:")) txTtlMs else intervalMs
                (now - entry.value) > ttl
            }
            .map { it.key }
        expiredKeys.forEach { dedupCache.remove(it) }
        // Size guard AFTER cleanup — only triggers if truly overloaded
        if (dedupCache.size > 1000) dedupCache.clear()

        // === Priority 1: TransactionId-based dedup (precise) ===
        // NOTE: txKey does NOT include packageName!
        // Same GD can arrive from BOTH push notification (com.mbmobile)
        // AND SMS notification (com.google.android.apps.messaging).
        // If we include packageName, they get different keys → both forward → DUPLICATE.
        // By using "TX:" prefix without packageName, HS_073_500000 is deduped
        // regardless of which app delivered it.
        if (!transactionId.isNullOrEmpty()) {
            val txPrefix = "TX:"
            val txKey = "$txPrefix$transactionId"
            val txIntervalMs = 24 * 60 * 60 * 1000L // 24 hours for transaction IDs

            // -------------------------------------------------------
            // STRATEGY: Use putIfAbsent() for EXACT match (ATOMIC, no race condition).
            // Then do prefix scan ONLY for FT codes (InboxStyle truncation).
            //
            // WHY this matters:
            // - putIfAbsent is a SINGLE atomic operation on ConcurrentHashMap
            // - No gap between read and write → no race condition
            // - 2 threads calling isDuplicate("HS_193") simultaneously:
            //   Thread 1: putIfAbsent("TX:HS_193", now) → null → NEW
            //   Thread 2: putIfAbsent("TX:HS_193", now) → returns Thread1's timestamp → DUPLICATE
            // -------------------------------------------------------

            // Step 1: EXACT match using putIfAbsent (ATOMIC)
            val existingTimestamp = dedupCache.putIfAbsent(txKey, now)
            if (existingTimestamp != null) {
                val age = now - existingTimestamp
                if (age <= txIntervalMs) {
                    Log.w(TAG, "<<< DUPLICATE by TransactionId EXACT: " +
                            "txId=$transactionId age=${age}ms")
                    return true
                } else {
                    // Expired — refresh
                    dedupCache[txKey] = now
                    Log.d(TAG, ">>> Expired TX entry refreshed: $transactionId")
                    return false
                }
            }

            // Step 2: PREFIX match ONLY for FT codes (InboxStyle truncation protection)
            // FT codes can be truncated in InboxStyle: "FT2627902" (truncated) vs "FT26279025922201" (full)
            // Other types (HS, ACSP, MGD, GD, VPBFT) are NEVER truncated → use EXACT match only
            //
            // WHY only FT: InboxStyle cuts at ~45 chars. FT codes are typically at the END
            // of the notification text, so they get cut. HS/MGD/ACSP are short and at the
            // beginning, so they survive truncation intact.
            if (transactionId.startsWith("FT") || transactionId.startsWith("VPBFT")) {
                val prefixEntry = dedupCache.entries.firstOrNull { entry ->
                    if (entry.key == txKey) return@firstOrNull false // Skip self (just inserted)
                    if (!entry.key.startsWith(txPrefix)) return@firstOrNull false
                    val existingTxId = entry.key.substringAfter(txPrefix)
                    // Only prefix match between FT-type codes
                    if (!existingTxId.startsWith("FT") && !existingTxId.startsWith("VPBFT")) return@firstOrNull false
                    existingTxId.startsWith(transactionId) || transactionId.startsWith(existingTxId)
                }

                if (prefixEntry != null) {
                    val age = now - prefixEntry.value
                    if (age <= txIntervalMs) {
                        Log.w(TAG, "<<< DUPLICATE by FT prefix: " +
                                "new=$transactionId existing=${prefixEntry.key.substringAfter(txPrefix)} " +
                                "age=${age}ms")
                        // Keep the LONGER (more complete) key, remove shorter
                        if (txKey.length > prefixEntry.key.length) {
                            dedupCache.remove(prefixEntry.key)
                            // txKey already inserted by putIfAbsent above
                        } else {
                            dedupCache.remove(txKey) // Remove shorter (self)
                        }
                        return true
                    }
                }
            }

            // Step 3: HS match (InboxStyle line without amount vs bigText with amount)
            // InboxStyle line: "CUSTOMER HOC PHI HS193..." → txId "HS_193" (no amount)
            // bigText (already forwarded): "...+400,000 VND...HS193" → txId "HS_193_400000"
            //
            // ONLY match when ONE side has no amount (InboxStyle truncation).
            // If BOTH have amounts, they are DIFFERENT transactions even if same HS.
            // Real case: parent pays 800K + 600K for same HS029 → BOTH must forward.
            if (transactionId.startsWith("HS_")) {
                val hsEntry = dedupCache.entries.firstOrNull { entry ->
                    if (entry.key == txKey) return@firstOrNull false
                    if (!entry.key.startsWith(txPrefix)) return@firstOrNull false
                    val existingTxId = entry.key.substringAfter(txPrefix)
                    if (!existingTxId.startsWith("HS_")) return@firstOrNull false

                    // PREFIX match: one txId is a prefix of the other
                    // This handles ALL InboxStyle vs bigText scenarios:
                    //
                    // InboxStyle "HS_073"           PREFIX of bigText "HS_073_900000_25778420" ✅
                    // InboxStyle "HS_073_900000"    PREFIX of bigText "HS_073_900000_25778420" ✅
                    // bigText "HS_073_900000_25778420" PREFIX of InboxStyle "HS_073" ✅
                    //
                    // But NOT for different GDs (both have full SD):
                    // Father "HS_073_300000_26000000" NOT PREFIX of Mother "HS_073_300000_26300000" ✅
                    // Different amounts: "HS_073_800000_SD1" NOT PREFIX of "HS_073_600000_SD2" ✅
                    existingTxId.startsWith(transactionId) || transactionId.startsWith(existingTxId)
                }

                if (hsEntry != null) {
                    val age = now - hsEntry.value
                    if (age <= txIntervalMs) {
                        Log.w(TAG, "<<< DUPLICATE by HS number: " +
                                "new=$transactionId existing=${hsEntry.key.substringAfter(txPrefix)} " +
                                "age=${age}ms")
                        // Keep the MORE SPECIFIC key (with amount), remove generic
                        if (txKey.length > hsEntry.key.length) {
                            dedupCache.remove(hsEntry.key)
                        } else {
                            dedupCache.remove(txKey)
                        }
                        return true
                    }
                }
            }

            Log.d(TAG, ">>> New transaction by ID: $transactionId")
            return false
        }

        // === Priority 2: Amount + time cross-app dedup ===
        // When no txId (people write "HOC PHI CON TRAI" without HSxxx),
        // same GD arrives from push + SMS with DIFFERENT packageName/title/postTime
        // → fingerprint won't match. But same AMOUNT within 30s = very likely same GD.
        //
        // Key format: "AMT:500000" (amount only, no packageName)
        // 30s window: push and SMS for same GD always arrive within seconds
        val amountMatch = REGEX_AMT.find(content) ?: REGEX_AMT.find(title)
        if (amountMatch != null) {
            val amountClean = amountMatch.groupValues[1].replace(REGEX_SEP, "")
            if (amountClean.length >= 4) { // Only for amounts >= 1000 to avoid false matches
                val amtKey = "AMT:$amountClean"
                val amtExisting = dedupCache.putIfAbsent(amtKey, now)
                if (amtExisting != null) {
                    val age = now - amtExisting
                    if (age <= 5_000L) { // 5 seconds — push and SMS always arrive within 1-2s
                        Log.w(TAG, "<<< DUPLICATE by amount+time: " +
                                "amount=$amountClean age=${age}ms pkg=$packageName")
                        return true
                    } else {
                        dedupCache[amtKey] = now
                    }
                }
            }
        }

        // === Priority 3: Full string fingerprint (last resort) ===
        // Include postTime (rounded to seconds) to distinguish 2 identical transfers
        // at different times. Without this, "NGUYEN VAN A +500,000" sent twice in 5s
        // would be deduped as false positive → LOST transaction.
        val fingerprint = "$packageName|$title|$content|${postTime / 1000}"
        val existingTimestamp = dedupCache.putIfAbsent(fingerprint, now)

        if (existingTimestamp != null) {
            val age = now - existingTimestamp
            if (age <= intervalMs) {
                Log.w(TAG, "<<< DUPLICATE by fingerprint: pkg=$packageName " +
                        "title='${title.take(30)}' content='${content.take(60)}...' " +
                        "age=${age}ms interval=${intervalMs}ms")
                return true
            } else {
                dedupCache[fingerprint] = now
                Log.d(TAG, ">>> Expired entry refreshed")
                return false
            }
        }

        Log.d(TAG, ">>> New transaction: pkg=$packageName " +
                "title='${title.take(30)}' content='${content.take(60)}...'")
        return false
    }

    /**
     * Parse webhook headers string into a Map.
     * Format: "Key: Value" (one per line)
     */
    fun getHeadersMap(): Map<String, String> {
        val headersStr = webhookHeaders
        if (headersStr.isBlank()) return emptyMap()
        return headersStr.lines()
            .filter { it.contains(":") }
            .associate {
                val parts = it.split(":", limit = 2)
                parts[0].trim() to parts[1].trim()
            }
    }

    // --- Logs ---
    // logLock prevents race condition between addLog (listener thread) and updateLog (worker thread)
    private val logLock = Any()

    fun getLogs(): MutableList<LogEntry> {
        val json = prefs.getString(KEY_LOGS, "[]") ?: "[]"
        val type = object : TypeToken<MutableList<LogEntry>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun addLog(entry: LogEntry) = synchronized(logLock) {
        val logs = getLogs()
        logs.add(0, entry)
        while (logs.size > MAX_LOGS) {
            logs.removeAt(logs.size - 1)
        }
        prefs.edit().putString(KEY_LOGS, gson.toJson(logs)).apply()
    }

    fun updateLog(id: Long, status: LogEntry.Status, responseCode: Int = 0, errorMessage: String = "") = synchronized(logLock) {
        val logs = getLogs()
        val index = logs.indexOfFirst { it.id == id }
        if (index >= 0) {
            logs[index] = logs[index].copy(
                status = status,
                responseCode = responseCode,
                errorMessage = errorMessage
            )
            prefs.edit().putString(KEY_LOGS, gson.toJson(logs)).apply()
        }
    }

    fun clearLogs() = synchronized(logLock) {
        prefs.edit().putString(KEY_LOGS, "[]").apply()
    }
}
