package com.ttonline.gachno

/**
 * Represents an installed application's info for the filter list.
 */
data class AppInfo(
    val appName: String,
    val packageName: String,
    var isSelected: Boolean = false
)
