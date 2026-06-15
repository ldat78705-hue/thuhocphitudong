package com.ttonline.gachno

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
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
 * - Webhook URL input
 * - Test button
 * - App filter button
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
        try {
            unregisterReceiver(logUpdateReceiver)
        } catch (_: Exception) {}
    }

    private fun setupUI() {
        // --- Webhook URL ---
        binding.etWebhookUrl.setText(settings.webhookUrl)

        // Save URL when focus lost
        binding.etWebhookUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                settings.webhookUrl = binding.etWebhookUrl.text.toString().trim()
            }
        }

        // --- Forwarding Toggle ---
        binding.switchForwarding.isChecked = settings.isForwardingEnabled
        binding.switchForwarding.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isNotificationListenerEnabled()) {
                binding.switchForwarding.isChecked = false
                showEnableNotificationListenerDialog()
                return@setOnCheckedChangeListener
            }
            settings.isForwardingEnabled = isChecked
            updateServiceStatus()
        }

        // --- Service Status Button ---
        binding.btnGrantPermission.setOnClickListener {
            openNotificationListenerSettings()
        }

        // --- Test Webhook Button ---
        binding.btnTestWebhook.setOnClickListener {
            testWebhook()
        }

        // --- Test Notification Listener ---
        binding.btnTestNotification.setOnClickListener {
            testNotificationListener()
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

        // --- Test Full Flow ---
        binding.btnTestFullFlow.setOnClickListener {
            testFullFlow()
        }
    }

    private fun updateServiceStatus() {
        val isListenerEnabled = isNotificationListenerEnabled()
        val isForwarding = settings.isForwardingEnabled && isListenerEnabled

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
            binding.switchForwarding.isChecked = false
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

    private fun testWebhook() {
        val url = binding.etWebhookUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, getString(R.string.enter_webhook_url), Toast.LENGTH_SHORT).show()
            return
        }

        settings.webhookUrl = url
        binding.btnTestWebhook.isEnabled = false
        binding.btnTestWebhook.text = getString(R.string.testing)

        CoroutineScope(Dispatchers.Main).launch {
            val result = webhookSender.send(
                webhookUrl = url,
                appName = "GachNo Test",
                packageName = packageName,
                title = "Test Notification",
                content = "Đây là tin nhắn kiểm tra từ GachNo. This is a test message from GachNo.",
                deviceName = settings.deviceName
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
     * Like SmsForwarder's test feature - creates a fake bank message
     * and sends it through the same webhook pipeline.
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

        binding.btnTestFullFlow.isEnabled = false
        binding.tvTestResult.visibility = android.view.View.VISIBLE
        binding.tvTestResult.text = getString(R.string.test_flow_running)
        binding.tvTestResult.setTextColor(getColor(R.color.text_secondary))

        // Simulate a bank notification
        val testAppName = "MB Bank (Test)"
        val testPackage = "com.mbmobile"
        val testTitle = "Thông báo giao dịch"
        val testContent = "TK 0123456789 +500,000 VND lúc ${java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())}. SD: 1,200,000 VND. GachNo test."

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
                deviceName = settings.deviceName
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
