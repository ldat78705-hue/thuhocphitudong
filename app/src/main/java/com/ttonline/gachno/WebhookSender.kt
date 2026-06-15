package com.ttonline.gachno

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Sends notification data to the configured webhook URL via HTTP POST.
 */
class WebhookSender {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val TIMEOUT_SECONDS = 15L
        private const val MAX_RETRIES = 1
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    data class WebhookPayload(
        val app_name: String,
        val package_name: String,
        val title: String,
        val content: String,
        val timestamp: String,
        val device_name: String
    )

    data class WebhookResult(
        val success: Boolean,
        val responseCode: Int = 0,
        val errorMessage: String = ""
    )

    /**
     * Send notification data to webhook URL.
     * Runs on IO dispatcher, safe to call from coroutine.
     */
    suspend fun send(
        webhookUrl: String,
        appName: String,
        packageName: String,
        title: String,
        content: String,
        deviceName: String
    ): WebhookResult = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) {
            return@withContext WebhookResult(false, 0, "Webhook URL is empty")
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
        val payload = WebhookPayload(
            app_name = appName,
            package_name = packageName,
            title = title,
            content = content,
            timestamp = sdf.format(Date()),
            device_name = deviceName
        )

        val jsonBody = gson.toJson(payload)
        var lastError = ""
        var lastCode = 0

        // Try with retries
        for (attempt in 0..MAX_RETRIES) {
            try {
                val requestBody = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "GachNo/1.0")
                    .build()

                val response = client.newCall(request).execute()
                val code = response.code
                response.close()

                if (code in 200..299) {
                    return@withContext WebhookResult(true, code)
                } else {
                    lastCode = code
                    lastError = "HTTP $code"
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                lastCode = 0
            }

            // Wait before retry
            if (attempt < MAX_RETRIES) {
                kotlinx.coroutines.delay(2000)
            }
        }

        return@withContext WebhookResult(false, lastCode, lastError)
    }
}
