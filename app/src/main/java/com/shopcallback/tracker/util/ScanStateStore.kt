package com.shopcallback.tracker.util

import android.content.Context

interface ScanStateStore {
    fun getLastSeenId(): Long
    fun setLastSeenId(id: Long)
}

class SharedPrefsScanStateStore(context: Context) : ScanStateStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getLastSeenId(): Long = prefs.getLong(KEY_LAST_SEEN_ID, NOT_SET)

    override fun setLastSeenId(id: Long) {
        prefs.edit().putLong(KEY_LAST_SEEN_ID, id).apply()
    }

    companion object {
        private const val PREFS_NAME = "scan_state"
        private const val KEY_LAST_SEEN_ID = "last_seen_id"
        const val NOT_SET = -1L
    }
}
