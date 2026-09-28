package com.shopcallback.tracker.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ScanStateStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `defaults to about 30 days ago when never set`() {
        val store = SharedPrefsScanStateStore(context)
        val expectedFloor = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30) - 5_000
        val expectedCeiling = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30) + 5_000
        val value = store.getLastScannedAt()
        assertTrue(value in expectedFloor..expectedCeiling)
    }

    @Test
    fun `returns the stored value after being set`() {
        val store = SharedPrefsScanStateStore(context)
        store.setLastScannedAt(12345L)
        assertEquals(12345L, store.getLastScannedAt())
    }
}
