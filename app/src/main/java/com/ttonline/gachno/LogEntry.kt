package com.ttonline.gachno

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a single forwarded notification log entry.
 */
data class LogEntry(
    val id: Long = System.currentTimeMillis(),
    val appName: String,
    val packageName: String,
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: Status = Status.PENDING,
    val responseCode: Int = 0,
    val errorMessage: String = ""
) {
    enum class Status {
        PENDING,
        SUCCESS,
        FAILED
    }

    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun getStatusText(): String {
        return when (status) {
            Status.PENDING -> "⏳"
            Status.SUCCESS -> "✅"
            Status.FAILED -> "❌"
        }
    }
}
