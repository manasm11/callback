package com.shopcallback.tracker.sync

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSettingsTest {
    private fun settings() = SyncSettings(ApplicationProvider.getApplicationContext())

    @Test
    fun `defaults mean sync is off and nothing has happened yet`() {
        val settings = settings()
        assertEquals("", settings.serverUrl)
        assertNull(settings.serverId)
        assertEquals(0L, settings.cursor)
        assertNull(settings.lastSyncAt)
    }

    @Test
    fun `device id is created once and kept`() {
        val id = settings().deviceId
        assertTrue(id.isNotBlank())
        assertEquals(id, settings().deviceId)
    }

    @Test
    fun `values persist and can be cleared`() {
        settings().apply {
            serverUrl = "http://shop-pc:8787"
            serverId = "server-1"
            cursor = 42L
            lastSyncAt = 1234L
        }
        settings().apply {
            assertEquals("http://shop-pc:8787", serverUrl)
            assertEquals("server-1", serverId)
            assertEquals(42L, cursor)
            assertEquals(1234L, lastSyncAt)
            serverId = null
            lastSyncAt = null
        }
        assertNull(settings().serverId)
        assertNull(settings().lastSyncAt)
    }
}
