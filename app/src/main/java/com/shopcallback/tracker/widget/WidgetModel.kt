package com.shopcallback.tracker.widget

import android.content.Intent
import android.net.Uri
import com.shopcallback.tracker.data.CallbackThreadEntity
import com.shopcallback.tracker.ui.groupByDayNewestFirst
import com.shopcallback.tracker.ui.headerText
import com.shopcallback.tracker.ui.missedCallsSummary
import java.time.ZoneId
import java.util.Locale

/** What the home-screen widget shows; built without any Android widget machinery so it can be tested. */
data class WidgetModel(val total: Int, val sections: List<WidgetSection>)

data class WidgetSection(val header: String, val rows: List<WidgetRow>)

data class WidgetRow(val title: String, val detail: String, val phoneNumber: String)

/** Same grouping and text as the Pending tab: by latest missed call, newest day and caller first. */
fun buildWidgetModel(
    pending: List<CallbackThreadEntity>,
    now: Long,
    formatTime: (Long) -> String,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): WidgetModel = WidgetModel(
    total = pending.size,
    sections = groupByDayNewestFirst(pending, { it.lastMissedAt }, now, zone, locale).map { group ->
        WidgetSection(
            header = group.headerText(),
            rows = group.items.map { thread ->
                WidgetRow(
                    title = thread.displayName ?: thread.phoneNumber,
                    detail = "${formatTime(thread.lastMissedAt)} · ${missedCallsSummary(thread, now, zone, locale)}",
                    phoneNumber = thread.phoneNumber
                )
            }
        )
    }
)

/** Calls straight away when the app may place calls; otherwise opens the dialer so the tap still helps. */
fun callBackIntent(phoneNumber: String, canCall: Boolean): Intent =
    Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
