package com.shopcallback.tracker.util

import android.content.Context
import java.util.concurrent.TimeUnit

interface ScanStateStore {
    fun getLastScannedAt(): Long
    fun setLastScannedAt(timestamp: Long)
}

class SharedPrefsScanStateStore(context: Context) : ScanStateStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getLastScannedAt(): Long {
        val stored = prefs.getLong(KEY_LAST_SCANNED_AT, NOT_SET)
        return if (stored == NOT_SET) {
            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        } else {
            stored
        }
    }

    override fun setLastScannedAt(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_SCANNED_AT, timestamp).apply()
    }

    companion object {
        private const val PREFS_NAME = "scan_state"
        private const val KEY_LAST_SCANNED_AT = "last_scanned_at"
        private const val NOT_SET = -1L
    }
}
