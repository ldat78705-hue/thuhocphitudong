package com.ttonline.gachno

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.ttonline.gachno.databinding.ActivityAppFilterBinding

/**
 * Activity for selecting which installed apps should have their
 * notifications forwarded to the webhook.
 */
class AppFilterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppFilterBinding
    private lateinit var settings: SettingsManager
    private lateinit var adapter: AppListAdapter

    private var allApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsManager(this)

        setupToolbar()
        setupSearch()
        setupRecyclerView()
        loadApps()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.btnSelectAll.setOnClickListener {
            selectAll(true)
        }

        binding.btnDeselectAll.setOnClickListener {
            selectAll(false)
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s.toString())
            }
        })
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter { app, isSelected ->
            onAppToggled(app, isSelected)
        }
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
    }

    private fun loadApps() {
        val pm = packageManager
        val selectedApps = settings.getSelectedApps()

        // Get all installed apps (non-system apps + well-known banking apps)
        val installedApps = pm.getInstalledApplications(0)

        allApps = installedApps
            .filter { appInfo ->
                // Show non-system apps OR system apps that the user previously selected
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        selectedApps.contains(appInfo.packageName)
            }
            .map { appInfo ->
                AppInfo(
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    isSelected = selectedApps.contains(appInfo.packageName)
                )
            }
            .sortedWith(compareByDescending<AppInfo> { it.isSelected }.thenBy { it.appName })

        adapter.updateApps(allApps)
        updateCount()
    }

    private fun filterApps(query: String) {
        if (query.isBlank()) {
            adapter.updateApps(allApps)
        } else {
            val filtered = allApps.filter {
                it.appName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
            adapter.updateApps(filtered)
        }
    }

    private fun onAppToggled(app: AppInfo, isSelected: Boolean) {
        val selectedApps = settings.getSelectedApps().toMutableSet()
        if (isSelected) {
            selectedApps.add(app.packageName)
        } else {
            selectedApps.remove(app.packageName)
        }
        settings.setSelectedApps(selectedApps)
        updateCount()
    }

    private fun selectAll(selected: Boolean) {
        val selectedApps = if (selected) {
            allApps.map { it.packageName }.toMutableSet()
        } else {
            mutableSetOf()
        }
        settings.setSelectedApps(selectedApps)

        allApps.forEach { it.isSelected = selected }
        adapter.updateApps(allApps)
        updateCount()
    }

    private fun updateCount() {
        val count = settings.getSelectedApps().size
        binding.tvCount.text = if (count == 0) {
            getString(R.string.monitoring_all)
        } else {
            getString(R.string.monitoring_count, count)
        }
    }
}
