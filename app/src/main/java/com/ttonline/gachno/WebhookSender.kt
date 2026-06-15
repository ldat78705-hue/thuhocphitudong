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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Sends notification data to the configured webhook URL via HTTP POST.
 *
 * Supports configurable body template (Params) with placeholders:
 *   [title], [content], [app_name], [package_name], [device_name], [timestamp]
 *
 * Same concept as SmsForwarder's WebhookUtils - the Params field in the UI
 * lets the user define the exact JSON body that the server expects.
 */
class WebhookSender {

    companion object {
        private const val TAG = "GachNo_Webhook"
    }

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
     * Replace template placeholders in params body.
     * Same as SmsForwarder's webParams replacement logic.
     */
    private fun buildBody(
        template: String,
        appName: String,
        packageName: String,
        title: String,
        content: String,
        deviceName: String,
        timestamp: String
    ): String {
        return template
            .replace("[title]", escapeJson(title))
            .replace("[content]", escapeJson(content))
            .replace("[app_name]", escapeJson(appName))
            .replace("[package_name]", escapeJson(packageName))
            .replace("[device_name]", escapeJson(deviceName))
            .replace("[timestamp]", escapeJson(timestamp))
    }

    /**
     * Escape special characters for JSON string values.
     */
    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Detect content type from headers or body.
     */
    private fun detectMediaType(headers: Map<String, String>, body: String): okhttp3.MediaType {
        val contentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value

        return try {
            (contentType ?: "application/json; charset=utf-8").toMediaType()
        } catch (e: Exception) {
            "application/json; charset=utf-8".toMediaType()
        }
    }

    /**
     * Send notification data to webhook URL using the configured Params template.
     * Runs on IO dispatcher, safe to call from coroutine.
     *
     * @param paramsTemplate The body template string (e.g. {"text": "[title] [content]"})
     * @param headers Custom headers map
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
        maxRetries: Int = 1,
        paramsTemplate: String = """{"text": "[title] [content]"}""",
        headers: Map<String, String> = mapOf("Content-Type" to "application/json")
    ): WebhookResult = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) {
            return@withContext WebhookResult(false, 0, "Webhook URL is empty")
        }

        // Acquire WakeLock to prevent CPU sleep during network call
        var wakeLock: PowerManager.WakeLock? = null
        try {
            if (context != null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "GachNo::WebhookWakeLock"
                )
                wakeLock.acquire(60 * 1000L)
            }

            val client = buildClient(timeoutSeconds)
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
            val timestamp = sdf.format(Date())

            // Build body from template with placeholders replaced
            val bodyStr = buildBody(paramsTemplate, appName, packageName, title, content, deviceName, timestamp)
            val mediaType = detectMediaType(headers, bodyStr)

            Log.d(TAG, "Body: $bodyStr")

            var lastError = ""
            var lastCode = 0

            for (attempt in 0..maxRetries) {
                try {
                    val requestBody = bodyStr.toRequestBody(mediaType)
                    val requestBuilder = Request.Builder()
                        .url(webhookUrl)
                        .post(requestBody)
                        .addHeader("User-Agent", "GachNo/1.0")

                    // Add custom headers (skip Content-Type as it's set by media type)
                    headers.forEach { (key, value) ->
                        if (!key.equals("Content-Type", ignoreCase = true)) {
                            requestBuilder.addHeader(key, value)
                        }
                    }

                    val request = requestBuilder.build()

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

                if (attempt < maxRetries) {
                    val delayMs = (attempt + 1) * 2000L
                    Log.d(TAG, "Retrying in ${delayMs}ms...")
                    delay(delayMs)
                }
            }

            return@withContext WebhookResult(false, lastCode, lastError)
        } finally {
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
