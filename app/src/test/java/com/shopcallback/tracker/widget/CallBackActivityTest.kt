package com.shopcallback.tracker.widget

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CallBackActivityTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    private fun launch(number: String?): CallBackActivity {
        val intent = Intent(application, CallBackActivity::class.java)
        if (number != null) intent.putExtra(CallBackActivity.EXTRA_PHONE_NUMBER, number)
        return Robolectric.buildActivity(CallBackActivity::class.java, intent).create().get()
    }

    @Test
    fun `calls straight away when CALL_PHONE is granted, then finishes`() {
        shadowOf(application).grantPermissions(Manifest.permission.CALL_PHONE)

        val activity = launch("9876543210")

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CALL, started.action)
        assertEquals("tel:9876543210", started.data.toString())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `opens the dialer when CALL_PHONE was revoked, then finishes`() {
        shadowOf(application).denyPermissions(Manifest.permission.CALL_PHONE)

        val activity = launch("9876543210")

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, started.action)
        assertEquals("tel:9876543210", started.data.toString())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `finishes without starting anything when the number is missing`() {
        val activity = launch(null)

        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }
}
