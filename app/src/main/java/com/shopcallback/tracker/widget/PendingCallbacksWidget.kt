package com.shopcallback.tracker.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.shopcallback.tracker.MainActivity
import com.shopcallback.tracker.data.CallbackDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.Date

/** Home-screen list of pending callbacks, grouped by day like the Pending tab. Tap a row to call back. */
class PendingCallbacksWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val pending = CallbackDatabase.getInstance(context).callbackThreadDao().observePending().first()
        val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
        val model = buildWidgetModel(pending, System.currentTimeMillis(), { timeFormat.format(Date(it)) })
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

        provideContent {
            GlanceTheme {
                Content(model, canCall)
            }
        }
    }

    @Composable
    private fun Content(model: WidgetModel, canCall: Boolean) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).padding(8.dp)
        ) {
            Text(
                "Pending callbacks · ${model.total}",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.fillMaxWidth().padding(4.dp).clickable(actionStartActivity<MainActivity>())
            )
            if (model.sections.isEmpty()) {
                Text(
                    "All caught up",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                    modifier = GlanceModifier.padding(4.dp)
                )
            } else {
                LazyColumn {
                    model.sections.forEach { section ->
                        item {
                            Text(
                                section.header,
                                style = TextStyle(fontWeight = FontWeight.Medium, color = GlanceTheme.colors.primary),
                                modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp)
                            )
                        }
                        items(section.rows) { row ->
                            Column(
                                modifier = GlanceModifier.fillMaxWidth().padding(4.dp)
                                    .clickable(actionStartActivity(callBackIntent(row.phoneNumber, canCall)))
                            ) {
                                Text(row.title, style = TextStyle(fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface))
                                Text(row.detail, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PendingCallbacksWidget"

        /** Re-renders every placed instance; a no-op when none are on the home screen. Never throws. */
        suspend fun refreshAll(context: Context) {
            try {
                PendingCallbacksWidget().updateAll(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "widget refresh failed", e)
            }
        }
    }
}

class PendingCallbacksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PendingCallbacksWidget()
}
