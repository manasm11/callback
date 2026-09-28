package com.shopcallback.tracker.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.shopcallback.tracker.MainActivity

object NotificationHelper {
    const val CHANNEL_ID = "callback_watcher"
    const val NOTIFICATION_ID = 1

    fun pendingCountText(count: Int): String =
        if (count == 0) {
            "All caught up"
        } else {
            "$count customer${if (count == 1) "" else "s"} waiting for a callback"
        }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Callback watcher", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun buildNotification(context: Context, pendingCount: Int): Notification {
        val openIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Missed Call Callback Tracker")
            .setContentText(pendingCountText(pendingCount))
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
