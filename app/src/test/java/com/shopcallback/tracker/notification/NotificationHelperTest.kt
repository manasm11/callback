package com.shopcallback.tracker.notification

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationHelperTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `zero pending shows all caught up`() {
        assertEquals("All caught up", NotificationHelper.pendingCountText(0))
    }

    @Test
    fun `singular vs plural wording`() {
        assertEquals("1 customer waiting for a callback", NotificationHelper.pendingCountText(1))
        assertEquals("3 customers waiting for a callback", NotificationHelper.pendingCountText(3))
    }

    @Test
    fun `channel is created with low importance`() {
        NotificationHelper.ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `notification is ongoing and shows the pending text`() {
        val notification = NotificationHelper.buildNotification(context, 2)
        val shadow = shadowOf(notification)
        assertEquals("2 customers waiting for a callback", shadow.contentText)
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }
}
