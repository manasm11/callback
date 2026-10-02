package com.shopcallback.tracker.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
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
        val pendingFlow = CallbackDatabase.getInstance(context).callbackThreadDao().observePending()
        val initial = pendingFlow.first()
        val timeFormat = android.text.format.DateFormat.getTimeFormat(context)

        // Collect inside the composition: a running Glance session ignores updateAll (it only
        // recomposes), so values captured before provideContent would go stale.
        provideContent {
            val pending by pendingFlow.collectAsState(initial)
            val model = remember(pending) {
                buildWidgetModel(pending, System.currentTimeMillis(), { timeFormat.format(Date(it)) })
            }
            GlanceTheme {
                Content(context, model)
            }
        }
    }

    @Composable
    private fun Content(context: Context, model: WidgetModel) {
        Column(
            modifier = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(16.dp)
                .background(GlanceTheme.colors.widgetBackground).padding(8.dp)
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
                                    .clickable(
                                        actionStartActivity(
                                            Intent(context, CallBackActivity::class.java)
                                                .putExtra(CallBackActivity.EXTRA_PHONE_NUMBER, row.phoneNumber)
                                        )
                                    )
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
