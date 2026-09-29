package com.shopcallback.tracker.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanStateStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `defaults to NOT_SET when never set`() {
        val store = SharedPrefsScanStateStore(context)
        assertEquals(SharedPrefsScanStateStore.NOT_SET, store.getLastSeenId())
    }

    @Test
    fun `returns the stored value after being set`() {
        val store = SharedPrefsScanStateStore(context)
        store.setLastSeenId(12345L)
        assertEquals(12345L, store.getLastSeenId())
    }
}
