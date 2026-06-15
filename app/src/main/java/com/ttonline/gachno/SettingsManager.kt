package com.ttonline.gachno

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages all app settings via SharedPreferences.
 * Stores webhook URL, filtered apps, forwarding state, logs, and language preference.
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
        private const val MAX_LOGS = 100
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
