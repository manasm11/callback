package com.shopcallback.tracker.ui

/** e.g. "Last synced 2 min ago · 0 waiting to upload", or "Sync is off" with no server address. */
fun syncStatusText(serverUrl: String, lastSyncAt: Long?, waiting: Int, now: Long): String {
    if (serverUrl.isBlank()) return "Sync is off"
    val synced = if (lastSyncAt == null) "Never synced" else "Last synced ${ago(now - lastSyncAt)}"
    return "$synced · $waiting waiting to upload"
}

private fun ago(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 1440 -> "${minutes / 60} h ago"
        else -> "${minutes / 1440} d ago"
    }
}
