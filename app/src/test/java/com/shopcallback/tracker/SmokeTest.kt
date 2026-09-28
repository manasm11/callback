package com.shopcallback.tracker

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmokeTest {
    @Test
    fun `application context is available`() {
        val app = ApplicationProvider.getApplicationContext<CallbackTrackerApp>()
        assertNotNull(app)
    }
}
