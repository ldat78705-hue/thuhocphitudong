package com.ttonline.gachno

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages all app settings via SharedPreferences.
 * Stores webhook URL, filtered apps, forwarding state, logs, language preference,
 * duplicate filter, retry config, and editable test data.
 *
 * Based on SmsForwarder's SettingUtils approach but simplified.
 */
class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "gachno_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"
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
        // Last notification hash for duplicate detection
        private const val KEY_LAST_NOTIFY_HASH = "last_notify_hash"
        private const val KEY_LAST_NOTIFY_TIME = "last_notify_time"

        private const val MAX_LOGS = 200
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // --- Webhook URL ---
    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()

    // --- Device Name ---
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, android.os.Build.MODEL) ?: android.os.Build.MODEL
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    // --- Forwarding Toggle ---
    var isForwardingEnabled: Boolean
        get() = prefs.getBoolean(KEY_FORWARDING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FORWARDING_ENABLED, value).apply()

    // --- Language ---
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "vi") ?: "vi"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    // --- Duplicate Filter Interval (seconds, 0 = disabled) ---
    // SmsForwarder: duplicateMessagesLimits
    var duplicateInterval: Int
        get() = prefs.getInt(KEY_DUPLICATE_INTERVAL, 30)
        set(value) = prefs.edit().putInt(KEY_DUPLICATE_INTERVAL, value).apply()

    // --- Retry Times (0 = no retry, same as SmsForwarder requestRetryTimes) ---
    var retryTimes: Int
        get() = prefs.getInt(KEY_RETRY_TIMES, 1)
        set(value) = prefs.edit().putInt(KEY_RETRY_TIMES, value).apply()

    // --- Request Timeout (seconds, same as SmsForwarder requestTimeout) ---
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
        // If no apps selected, forward all
        return selectedApps.isEmpty() || selectedApps.contains(packageName)
    }

    // --- Duplicate Detection ---
    // Returns true if this notification is a duplicate (same content within interval)
    fun isDuplicate(packageName: String, title: String, content: String): Boolean {
        val interval = duplicateInterval
        if (interval <= 0) return false

        val hash = "$packageName|$title|$content".hashCode()
        val lastHash = prefs.getInt(KEY_LAST_NOTIFY_HASH, 0)
        val lastTime = prefs.getLong(KEY_LAST_NOTIFY_TIME, 0)
        val now = System.currentTimeMillis()

        if (hash == lastHash && (now - lastTime) < interval * 1000L) {
            return true
        }

        // Save current
        prefs.edit()
            .putInt(KEY_LAST_NOTIFY_HASH, hash)
            .putLong(KEY_LAST_NOTIFY_TIME, now)
            .apply()
        return false
    }

    // --- Logs ---
    fun getLogs(): MutableList<LogEntry> {
        val json = prefs.getString(KEY_LOGS, "[]") ?: "[]"
        val type = object : TypeToken<MutableList<LogEntry>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun addLog(entry: LogEntry) {
        val logs = getLogs()
        logs.add(0, entry) // Add to beginning
        // Keep only last MAX_LOGS entries
        while (logs.size > MAX_LOGS) {
            logs.removeAt(logs.size - 1)
        }
        prefs.edit().putString(KEY_LOGS, gson.toJson(logs)).apply()
    }

    fun updateLog(id: Long, status: LogEntry.Status, responseCode: Int = 0, errorMessage: String = "") {
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

    fun clearLogs() {
        prefs.edit().putString(KEY_LOGS, "[]").apply()
    }
}
