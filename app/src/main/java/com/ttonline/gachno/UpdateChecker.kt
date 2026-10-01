package com.ttonline.gachno

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks for app updates via GitHub Releases API.
 * - Compares current versionName with latest release tag
 * - Shows dialog with changelog + download link
 * - Non-intrusive: only shows once per version, user can dismiss
 */
class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "GachNo_Update"
        private const val GITHUB_API_URL =
            "https://api.github.com/repos/ldat78705-hue/thuhocphitudong/releases/latest"
        private const val PREF_SKIPPED_VERSION = "skipped_update_version"
    }

    data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("name") val name: String,
        @SerializedName("body") val body: String,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("assets") val assets: List<Asset>
    )

    data class Asset(
        @SerializedName("name") val name: String,
        @SerializedName("browser_download_url") val downloadUrl: String,
        @SerializedName("size") val size: Long
    )

    /**
     * Check for updates in background. Shows dialog on main thread if update available.
     * Safe to call from any coroutine scope.
     */
    suspend fun checkForUpdate(activity: androidx.appcompat.app.AppCompatActivity) {
        try {
            val release = fetchLatestRelease() ?: return

            val currentVersion = getCurrentVersion()
            val latestVersion = release.tagName.removePrefix("v")

            Log.d(TAG, "Current: $currentVersion, Latest: $latestVersion")

            if (!isNewerVersion(latestVersion, currentVersion)) {
                Log.d(TAG, "App is up to date")
                return
            }

            // Check if user already skipped this version
            val skipped = context.getSharedPreferences("gachno_prefs", Context.MODE_PRIVATE)
                .getString(PREF_SKIPPED_VERSION, "")
            if (skipped == latestVersion) {
                Log.d(TAG, "User skipped version $latestVersion")
                return
            }

            // Find APK asset
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }

            // Show update dialog on main thread
            withContext(Dispatchers.Main) {
                showUpdateDialog(activity, release, apkAsset, latestVersion)
            }

        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            // Silent fail - don't bother user
        }
    }

    private suspend fun fetchLatestRelease(): GitHubRelease? = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .addHeader("Accept", "application/vnd.github.v3+json")
            .addHeader("User-Agent", "GachNo-UpdateChecker")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                response.close()
                if (body != null) {
                    Gson().fromJson(body, GitHubRelease::class.java)
                } else null
            } else {
                response.close()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network error: ${e.message}")
            null
        }
    }

    private fun getCurrentVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    /**
     * Compare semantic versions. Returns true if 'latest' is newer than 'current'.
     * Handles: "1.9.5" > "1.9.4", "1.10.0" > "1.9.9", "2.0.0" > "1.99.99"
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun showUpdateDialog(
        activity: androidx.appcompat.app.AppCompatActivity,
        release: GitHubRelease,
        apkAsset: Asset?,
        latestVersion: String
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val sizeText = if (apkAsset != null) {
            val sizeMB = apkAsset.size / (1024.0 * 1024.0)
            "\n\n📦 Kích thước: %.1f MB".format(sizeMB)
        } else ""

        val message = buildString {
            append("🆕 Phiên bản mới: ${release.name}\n\n")
            // Clean up markdown from release body
            val changelog = release.body
                .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
                .trim()
            if (changelog.isNotEmpty()) {
                append(changelog)
            }
            append(sizeText)
            append("\n\nCài đặt bản mới giữ nguyên toàn bộ cấu hình cũ.")
        }

        AlertDialog.Builder(activity)
            .setTitle("📢 Có bản cập nhật mới!")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("⬇️ Tải ngay") { _, _ ->
                // Open download URL in browser
                val url = apkAsset?.downloadUrl ?: release.htmlUrl
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot open browser: ${e.message}")
                }
            }
            .setNegativeButton("Để sau", null)
            .setNeutralButton("Bỏ qua bản này") { _, _ ->
                // Remember to skip this version
                context.getSharedPreferences("gachno_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_SKIPPED_VERSION, latestVersion)
                    .apply()
            }
            .show()
    }
}
