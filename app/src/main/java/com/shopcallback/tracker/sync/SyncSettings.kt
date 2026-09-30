package com.shopcallback.tracker.sync

import android.content.Context
import java.util.UUID

/** Sync configuration and progress, persisted in SharedPreferences. */
class SyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** This install's random ID, created on first use. Keeps event IDs unique across phones. */
    val deviceId: String
        get() = synchronized(LOCK) {
            prefs.getString(KEY_DEVICE_ID, null)
                ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).commit() }
        }

    /** e.g. "http://shop-pc:8787". Empty means sync is off. */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SERVER_URL, value).commit() }

    /** The server's database ID last seen; a different one means the server was reset. */
    var serverId: String?
        get() = prefs.getString(KEY_SERVER_ID, null)
        set(value) { prefs.edit().putString(KEY_SERVER_ID, value).commit() }

    /** Highest server `seq` already pulled. */
    var cursor: Long
        get() = prefs.getLong(KEY_CURSOR, 0L)
        set(value) { prefs.edit().putLong(KEY_CURSOR, value).commit() }

    var lastSyncAt: Long?
        get() = prefs.getLong(KEY_LAST_SYNC_AT, NEVER).takeIf { it != NEVER }
        set(value) { prefs.edit().putLong(KEY_LAST_SYNC_AT, value ?: NEVER).commit() }

    companion object {
        const val PREFS_NAME = "sync_settings"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SERVER_ID = "server_id"
        private const val KEY_CURSOR = "cursor"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val NEVER = -1L
        private val LOCK = Any()
    }
}
