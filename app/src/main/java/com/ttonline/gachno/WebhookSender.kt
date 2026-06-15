package com.ttonline.gachno

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 *
 * Based on SmsForwarder's WebhookUtils approach:
 * - Configurable timeout (requestTimeout)
 * - Configurable retry (requestRetryTimes)
 * - WakeLock to prevent CPU sleep during send
 * - JSON payload with Content-Type header
 * - User-Agent header
 */
class WebhookSender {

    companion object {
        private const val TAG = "GachNo_Webhook"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

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
     * Build OkHttpClient with dynamic timeout.
     */
    private fun buildClient(timeoutSeconds: Long): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Send notification data to webhook URL.
     * Runs on IO dispatcher, safe to call from coroutine.
     *
     * @param context Optional context for WakeLock
     * @param timeoutSeconds Configurable timeout (from settings)
     * @param maxRetries Number of retries on failure (from settings)
     */
    suspend fun send(
        webhookUrl: String,
        appName: String,
        packageName: String,
        title: String,
        content: String,
        deviceName: String,
        context: Context? = null,
        timeoutSeconds: Long = 15L,
        maxRetries: Int = 1
    ): WebhookResult = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) {
            return@withContext WebhookResult(false, 0, "Webhook URL is empty")
        }

        // Acquire WakeLock to prevent CPU sleep during network call
        // Same approach as SmsForwarder to ensure reliable delivery
        var wakeLock: PowerManager.WakeLock? = null
        try {
            if (context != null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "GachNo::WebhookWakeLock"
                )
                wakeLock.acquire(60 * 1000L) // Max 60 seconds
            }

            val client = buildClient(timeoutSeconds)

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

            // Try with configurable retries
            for (attempt in 0..maxRetries) {
                try {
                    val requestBody = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
                    val request = Request.Builder()
                        .url(webhookUrl)
                        .post(requestBody)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("User-Agent", "GachNo/1.0")
                        .build()

                    Log.d(TAG, "Sending webhook (attempt ${attempt + 1}/${maxRetries + 1}): $webhookUrl")

                    val response = client.newCall(request).execute()
                    val code = response.code
                    response.close()

                    if (code in 200..299) {
                        Log.d(TAG, "Webhook success: HTTP $code")
                        return@withContext WebhookResult(true, code)
                    } else {
                        lastCode = code
                        lastError = "HTTP $code"
                        Log.w(TAG, "Webhook non-success: HTTP $code")
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "Unknown error"
                    lastCode = 0
                    Log.e(TAG, "Webhook error (attempt ${attempt + 1}): $lastError")
                }

                // Wait before retry (increasing delay)
                if (attempt < maxRetries) {
                    val delayMs = (attempt + 1) * 2000L
                    Log.d(TAG, "Retrying in ${delayMs}ms...")
                    delay(delayMs)
                }
            }

            return@withContext WebhookResult(false, lastCode, lastError)
        } finally {
            // Always release WakeLock
            try {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "WakeLock release error: ${e.message}")
            }
        }
    }
}
