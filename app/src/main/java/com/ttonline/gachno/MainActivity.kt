package com.ttonline.gachno

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.ttonline.gachno.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Main screen of GachNo app.
 * Single-screen UI with:
 * - Forwarding toggle (on/off)
 * - Webhook URL input + test
 * - App filter button
 * - Full-flow test with editable fields (saved for reuse)
 * - Battery optimization bypass
 * - Recent forwarding logs
 * - Language switch (Việt/English)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsManager
    private lateinit var logAdapter: LogAdapter
    private val webhookSender = WebhookSender()

    // Receiver to update log list when a notification is forwarded
    private val logUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshLogs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = SettingsManager(this)
        applyLanguage(settings.language)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        refreshLogs()

        // Start ForegroundService if forwarding is enabled
        if (settings.isForwardingEnabled && isNotificationListenerEnabled()) {
            ForegroundService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        refreshLogs()

        val filter = IntentFilter("com.ttonline.gachno.LOG_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logUpdateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        // Save all editable fields when leaving
        saveTestFields()
        saveWebhookFields()
        try {
            unregisterReceiver(logUpdateReceiver)
        } catch (_: Exception) {}
    }

    private fun setupUI() {
        // --- Web link ---
        binding.tvWebLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tt.thaydat.edu.vn")))
        }

        // --- Webhook URL ---
        binding.etWebhookUrl.setText(settings.webhookUrl)

        // --- Webhook Params (body template) ---
        binding.etWebhookParams.setText(settings.webhookParams)

        // --- Webhook Headers ---
        binding.etWebhookHeaders.setText(settings.webhookHeaders)

        // Save URL when focus lost
        binding.etWebhookUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                settings.webhookUrl = binding.etWebhookUrl.text.toString().trim()
            }
        }

        // --- Forwarding Toggle ---
        binding.switchForwarding.isChecked = settings.isForwardingEnabled
        binding.switchForwarding.setOnCheckedChangeListener { _, isChecked ->
            onForwardingToggled(isChecked)
        }

        // --- Service Status Button ---
        binding.btnGrantPermission.setOnClickListener {
            openNotificationListenerSettings()
        }

        // --- Battery Optimization ---
        binding.btnBatteryOptimize.setOnClickListener {
            requestDisableBatteryOptimization()
        }

        // --- Test Notification Listener ---
        binding.btnTestNotification.setOnClickListener {
            testNotificationListener()
        }

        // --- Test Webhook Button ---
        binding.btnTestWebhook.setOnClickListener {
            testWebhook()
        }

        // --- Select Apps Button ---
        binding.btnSelectApps.setOnClickListener {
            startActivity(Intent(this, AppFilterActivity::class.java))
        }

        // --- Clear Logs Button ---
        binding.btnClearLogs.setOnClickListener {
            settings.clearLogs()
            refreshLogs()
            Toast.makeText(this, getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
        }

        // --- Language Switch ---
        binding.btnLanguage.setOnClickListener {
            val newLang = if (settings.language == "vi") "en" else "vi"
            settings.language = newLang
            applyLanguage(newLang)
            recreate()
        }
        updateLanguageButton()

        // --- Log RecyclerView ---
        logAdapter = LogAdapter()
        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = logAdapter

        // --- Show selected apps count ---
        updateSelectedAppsCount()

        // --- Load saved test fields ---
        loadTestFields()

        // --- Test Full Flow ---
        binding.btnTestFullFlow.setOnClickListener {
            saveTestFields()
            testFullFlow()
        }
    }

    private fun onForwardingToggled(isChecked: Boolean) {
        if (isChecked && !isNotificationListenerEnabled()) {
            // Temporarily remove listener to prevent loop
            binding.switchForwarding.setOnCheckedChangeListener(null)
            binding.switchForwarding.isChecked = false
            binding.switchForwarding.setOnCheckedChangeListener { _, checked ->
                onForwardingToggled(checked)
            }
            showEnableNotificationListenerDialog()
            return
        }
        settings.isForwardingEnabled = isChecked
        if (isChecked) {
            ForegroundService.start(this)
        } else {
            ForegroundService.stop(this)
        }
        updateServiceStatus()
        // Prevent auto-scroll
        binding.switchForwarding.clearFocus()
        binding.scrollView.post {
            binding.scrollView.scrollTo(0, 0)
        }
    }

    private fun updateServiceStatus() {
        val isListenerEnabled = isNotificationListenerEnabled()
        val isForwarding = settings.isForwardingEnabled

        // Temporarily remove listener to prevent triggering onCheckedChanged
        binding.switchForwarding.setOnCheckedChangeListener(null)
        binding.switchForwarding.isChecked = isForwarding
        // Re-attach listener
        binding.switchForwarding.setOnCheckedChangeListener { _, isChecked ->
            onForwardingToggled(isChecked)
        }

        if (isListenerEnabled) {
            binding.tvServiceStatus.text = if (isForwarding) {
                getString(R.string.status_active)
            } else {
                getString(R.string.status_paused)
            }
            binding.tvServiceStatus.setTextColor(
                if (isForwarding) getColor(R.color.status_active)
                else getColor(R.color.status_paused)
            )
            binding.btnGrantPermission.text = getString(R.string.permission_granted)
            binding.btnGrantPermission.isEnabled = false
        } else {
            binding.tvServiceStatus.text = getString(R.string.status_no_permission)
            binding.tvServiceStatus.setTextColor(getColor(R.color.status_error))
            binding.btnGrantPermission.text = getString(R.string.grant_permission)
            binding.btnGrantPermission.isEnabled = true
        }
    }

    private fun updateSelectedAppsCount() {
        val count = settings.getSelectedApps().size
        binding.tvSelectedApps.text = if (count == 0) {
            getString(R.string.all_apps_selected)
        } else {
            getString(R.string.apps_selected_count, count)
        }
    }

    private fun updateLanguageButton() {
        binding.btnLanguage.text = if (settings.language == "vi") "EN" else "VI"
    }

    private fun refreshLogs() {
        val logs = settings.getLogs()
        logAdapter.updateLogs(logs)

        binding.tvLogCount.text = getString(R.string.log_count, logs.size)

        if (logs.isEmpty()) {
            binding.tvEmptyLogs.visibility = android.view.View.VISIBLE
            binding.rvLogs.visibility = android.view.View.GONE
        } else {
            binding.tvEmptyLogs.visibility = android.view.View.GONE
            binding.rvLogs.visibility = android.view.View.VISIBLE
        }

        updateSelectedAppsCount()
    }

    // ==================== TEST FUNCTIONS ====================

    /**
     * Test webhook connection (simple ping test)
     */
    private fun testWebhook() {
        val url = binding.etWebhookUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, getString(R.string.enter_webhook_url), Toast.LENGTH_SHORT).show()
            return
        }

        settings.webhookUrl = url
        binding.btnTestWebhook.isEnabled = false
        binding.btnTestWebhook.text = getString(R.string.testing)

        // Save params/headers before testing
        saveWebhookFields()

        CoroutineScope(Dispatchers.Main).launch {
            val result = webhookSender.send(
                webhookUrl = url,
                appName = "GachNo Test",
                packageName = packageName,
                title = "Test Notification",
                content = "Đây là tin nhắn kiểm tra từ GachNo. This is a test message from GachNo.",
                deviceName = settings.deviceName,
                context = this@MainActivity,
                timeoutSeconds = settings.requestTimeout.toLong(),
                maxRetries = 0, // No retry for quick test
                paramsTemplate = settings.webhookParams,
                headers = settings.getHeadersMap()
            )

            binding.btnTestWebhook.isEnabled = true
            binding.btnTestWebhook.text = getString(R.string.test_webhook)

            if (result.success) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.test_success, result.responseCode),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.test_failed, result.errorMessage),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Test notification listener connection status
     */
    private fun testNotificationListener() {
        val isConnected = isNotificationListenerEnabled()
        val msg = if (isConnected) {
            getString(R.string.test_notif_ok)
        } else {
            getString(R.string.test_notif_fail)
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /**
     * Test full flow: simulate a bank notification and send it to webhook.
     * Uses editable fields that are saved/restored across sessions.
     */
    private fun testFullFlow() {
        // Check permission first
        if (!isNotificationListenerEnabled()) {
            binding.tvTestResult.visibility = android.view.View.VISIBLE
            binding.tvTestResult.text = getString(R.string.test_flow_no_permission)
            binding.tvTestResult.setTextColor(getColor(R.color.status_error))
            return
        }

        // Check webhook URL
        val url = binding.etWebhookUrl.text.toString().trim()
        if (url.isBlank()) {
            binding.tvTestResult.visibility = android.view.View.VISIBLE
            binding.tvTestResult.text = getString(R.string.test_flow_no_url)
            binding.tvTestResult.setTextColor(getColor(R.color.status_error))
            return
        }
        settings.webhookUrl = url

        // Get editable test data
        val testAppName = binding.etTestAppName.text.toString().trim().ifBlank { "MB Bank (Test)" }
        val testPackage = "com.test.app"
        val testTitle = binding.etTestTitle.text.toString().trim().ifBlank { "Thông báo giao dịch" }
        val testContent = binding.etTestContent.text.toString().trim().ifBlank {
            "TK 0123456789 +500,000 VND. SD: 1,200,000 VND. GachNo test."
        }

        binding.btnTestFullFlow.isEnabled = false
        binding.tvTestResult.visibility = android.view.View.VISIBLE
        binding.tvTestResult.text = getString(R.string.test_flow_running)
        binding.tvTestResult.setTextColor(getColor(R.color.text_secondary))

        // Add log entry
        val logEntry = LogEntry(
            appName = testAppName,
            packageName = testPackage,
            title = testTitle,
            content = testContent
        )
        settings.addLog(logEntry)

        CoroutineScope(Dispatchers.Main).launch {
            val result = webhookSender.send(
                webhookUrl = url,
                appName = testAppName,
                packageName = testPackage,
                title = testTitle,
                content = testContent,
                deviceName = settings.deviceName,
                context = this@MainActivity,
                timeoutSeconds = settings.requestTimeout.toLong(),
                maxRetries = settings.retryTimes,
                paramsTemplate = settings.webhookParams,
                headers = settings.getHeadersMap()
            )

            binding.btnTestFullFlow.isEnabled = true

            if (result.success) {
                settings.updateLog(logEntry.id, LogEntry.Status.SUCCESS, result.responseCode)
                binding.tvTestResult.text = getString(R.string.test_flow_success, result.responseCode)
                binding.tvTestResult.setTextColor(getColor(R.color.status_active))
            } else {
                settings.updateLog(logEntry.id, LogEntry.Status.FAILED, result.responseCode, result.errorMessage)
                binding.tvTestResult.text = getString(R.string.test_flow_failed, result.errorMessage)
                binding.tvTestResult.setTextColor(getColor(R.color.status_error))
            }

            refreshLogs()
        }
    }

    // ==================== TEST FIELD PERSISTENCE ====================

    private fun loadTestFields() {
        binding.etTestAppName.setText(settings.testAppName)
        binding.etTestTitle.setText(settings.testTitle)
        binding.etTestContent.setText(settings.testContent)
    }

    private fun saveTestFields() {
        val appName = binding.etTestAppName.text.toString().trim()
        val title = binding.etTestTitle.text.toString().trim()
        val content = binding.etTestContent.text.toString().trim()

        if (appName.isNotEmpty()) settings.testAppName = appName
        if (title.isNotEmpty()) settings.testTitle = title
        if (content.isNotEmpty()) settings.testContent = content
    }

    private fun saveWebhookFields() {
        val params = binding.etWebhookParams.text.toString().trim()
        val headers = binding.etWebhookHeaders.text.toString().trim()
        val url = binding.etWebhookUrl.text.toString().trim()

        if (url.isNotEmpty()) settings.webhookUrl = url
        if (params.isNotEmpty()) settings.webhookParams = params
        if (headers.isNotEmpty()) settings.webhookHeaders = headers
    }

    // ==================== BATTERY OPTIMIZATION ====================

    @SuppressLint("BatteryLife")
    private fun requestDisableBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, getString(R.string.battery_already_disabled), Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback to battery settings
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
    }

    // ==================== HELPERS ====================

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, NotifyListenerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun showEnableNotificationListenerDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required))
            .setMessage(getString(R.string.permission_message))
            .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                openNotificationListenerSettings()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun openNotificationListenerSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun applyLanguage(lang: String) {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
