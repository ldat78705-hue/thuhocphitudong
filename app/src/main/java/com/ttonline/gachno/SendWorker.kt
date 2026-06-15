package com.ttonline.gachno

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager worker that sends webhook in background.
 * Same approach as SmsForwarder's SendWorker:
 * - Survives app process death
 * - Android guarantees execution even if app is killed
 * - Handles retries at WorkManager level
 */
class SendWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "GachNo_Worker"
        const val KEY_WEBHOOK_URL = "webhook_url"
        const val KEY_APP_NAME = "app_name"
        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_TITLE = "title"
        const val KEY_CONTENT = "content"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_PARAMS_TEMPLATE = "params_template"
        const val KEY_HEADERS_JSON = "headers_json"
        const val KEY_LOG_ID = "log_id"
        const val KEY_TIMEOUT = "timeout"
        const val KEY_MAX_RETRIES = "max_retries"
    }

    override suspend fun doWork(): Result {
        val webhookUrl = inputData.getString(KEY_WEBHOOK_URL) ?: return Result.failure()
        val appName = inputData.getString(KEY_APP_NAME) ?: ""
        val packageName = inputData.getString(KEY_PACKAGE_NAME) ?: ""
        val title = inputData.getString(KEY_TITLE) ?: ""
        val content = inputData.getString(KEY_CONTENT) ?: ""
        val deviceName = inputData.getString(KEY_DEVICE_NAME) ?: ""
        val paramsTemplate = inputData.getString(KEY_PARAMS_TEMPLATE) ?: ""
        val headersJson = inputData.getString(KEY_HEADERS_JSON) ?: ""
        val logId = inputData.getLong(KEY_LOG_ID, 0L)
        val timeout = inputData.getLong(KEY_TIMEOUT, 10)
        val maxRetries = inputData.getInt(KEY_MAX_RETRIES, 3)

        Log.d(TAG, "SendWorker executing for: $appName [$packageName]")

        val settings = SettingsManager(applicationContext)

        // Parse headers from JSON
        val headers = try {
            if (headersJson.isNotEmpty()) {
                val map = mutableMapOf<String, String>()
                val pairs = headersJson.split("|||")
                for (pair in pairs) {
                    val kv = pair.split(":::")
                    if (kv.size == 2) {
                        map[kv[0]] = kv[1]
                    }
                }
                map
            } else {
                settings.getHeadersMap()
            }
        } catch (e: Exception) {
            settings.getHeadersMap()
        }

        val sender = WebhookSender()
        val result = sender.send(
            webhookUrl = webhookUrl,
            appName = appName,
            packageName = packageName,
            title = title,
            content = content,
            deviceName = deviceName,
            context = applicationContext,
            timeoutSeconds = timeout,
            maxRetries = maxRetries,
            paramsTemplate = paramsTemplate,
            headers = headers
        )

        if (result.success) {
            settings.updateLog(logId, LogEntry.Status.SUCCESS, result.responseCode)
            Log.d(TAG, "Webhook sent successfully: ${result.responseCode}")

            // Notify MainActivity
            try {
                val updateIntent = Intent("com.ttonline.gachno.LOG_UPDATED")
                applicationContext.sendBroadcast(updateIntent)
            } catch (_: Exception) {}

            return Result.success()
        } else {
            settings.updateLog(logId, LogEntry.Status.FAILED, result.responseCode, result.errorMessage)
            Log.e(TAG, "Webhook failed: ${result.errorMessage}")

            // Retry if possible
            return if (runAttemptCount < maxRetries) {
                Log.d(TAG, "Will retry (attempt ${runAttemptCount + 1}/$maxRetries)")
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
