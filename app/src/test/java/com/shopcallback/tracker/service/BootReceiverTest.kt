package com.shopcallback.tracker.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `boot completed starts the watcher service`() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val nextService = shadowOf(context).nextStartedService
        assertEquals(CallWatcherService::class.java.name, nextService?.component?.className)
    }

    @Test
    fun `other actions are ignored`() {
        BootReceiver().onReceive(context, Intent("some.other.action"))

        assertNull(shadowOf(context).nextStartedService)
    }
}
