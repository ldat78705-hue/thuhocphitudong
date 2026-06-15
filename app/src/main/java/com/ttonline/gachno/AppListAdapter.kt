package com.ttonline.gachno

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for the app filter selection list.
 * Shows installed apps with checkboxes to select which apps to monitor.
 */
class AppListAdapter(
    private var apps: List<AppInfo> = emptyList(),
    private val onAppToggled: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        val tvPackage: TextView = itemView.findViewById(R.id.tvAppPackage)
        val cbSelected: CheckBox = itemView.findViewById(R.id.cbAppSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_filter, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]

        holder.tvName.text = app.appName
        holder.tvPackage.text = app.packageName
        holder.cbSelected.isChecked = app.isSelected

        // Load app icon
        try {
            val pm = holder.itemView.context.packageManager
            val icon = pm.getApplicationIcon(app.packageName)
            holder.ivIcon.setImageDrawable(icon)
        } catch (e: Exception) {
            holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        // Click listeners
        val toggleAction = {
            app.isSelected = !app.isSelected
            holder.cbSelected.isChecked = app.isSelected
            onAppToggled(app, app.isSelected)
        }

        holder.itemView.setOnClickListener { toggleAction() }
        holder.cbSelected.setOnClickListener {
            app.isSelected = holder.cbSelected.isChecked
            onAppToggled(app, app.isSelected)
        }
    }

    override fun getItemCount() = apps.size

    fun updateApps(newApps: List<AppInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }
}
