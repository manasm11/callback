package com.shopcallback.tracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusTest {
    private val now = 10 * 24 * 60 * 60 * 1000L
    private val minute = 60_000L

    @Test
    fun `sync off`() {
        assertEquals("Sync is off", syncStatusText("", lastSyncAt = null, waiting = 3, now = now))
    }

    @Test
    fun `never synced`() {
        assertEquals("Never synced · 2 waiting to upload", syncStatusText("http://pc:8787", null, 2, now))
    }

    @Test
    fun `last sync time is shown in rough units`() {
        assertEquals("Last synced just now · 0 waiting to upload", syncStatusText("http://pc:8787", now - 20_000, 0, now))
        assertEquals("Last synced 2 min ago · 0 waiting to upload", syncStatusText("http://pc:8787", now - 2 * minute, 0, now))
        assertEquals("Last synced 3 h ago · 1 waiting to upload", syncStatusText("http://pc:8787", now - 185 * minute, 1, now))
        assertEquals("Last synced 2 d ago · 0 waiting to upload", syncStatusText("http://pc:8787", now - 2 * 1440 * minute, 0, now))
    }
}
