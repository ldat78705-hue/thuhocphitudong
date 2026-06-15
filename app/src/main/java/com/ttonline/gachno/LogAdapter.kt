package com.ttonline.gachno

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for displaying forwarded notification logs.
 */
class LogAdapter(private var logs: List<LogEntry> = emptyList()) :
    RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvStatus: TextView = itemView.findViewById(R.id.tvLogStatus)
        val tvApp: TextView = itemView.findViewById(R.id.tvLogApp)
        val tvTime: TextView = itemView.findViewById(R.id.tvLogTime)
        val tvTitle: TextView = itemView.findViewById(R.id.tvLogTitle)
        val tvContent: TextView = itemView.findViewById(R.id.tvLogContent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logs[position]

        holder.tvStatus.text = log.getStatusText()
        holder.tvApp.text = log.appName
        holder.tvTime.text = log.getFormattedTime()
        holder.tvTitle.text = log.title
        holder.tvContent.text = log.content

        // Color the card based on status
        val bgColor = when (log.status) {
            LogEntry.Status.SUCCESS -> R.color.log_success_bg
            LogEntry.Status.FAILED -> R.color.log_failed_bg
            LogEntry.Status.PENDING -> R.color.log_pending_bg
        }
        holder.itemView.setBackgroundColor(
            ContextCompat.getColor(holder.itemView.context, bgColor)
        )
    }

    override fun getItemCount() = logs.size

    fun updateLogs(newLogs: List<LogEntry>) {
        logs = newLogs
        notifyDataSetChanged()
    }
}
