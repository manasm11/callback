package com.shopcallback.tracker.ui

import com.shopcallback.tracker.data.CallbackThreadEntity
import java.time.ZoneId
import java.util.Locale

/** e.g. "1 missed call", or "3 missed calls since Monday" when the first miss was on an earlier day. */
fun missedCallsSummary(
    thread: CallbackThreadEntity,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val count = "${thread.attemptCount} missed call${if (thread.attemptCount == 1) "" else "s"}"
    val firstDay = dayLabel(thread.firstMissedAt, now, zone, locale)
    return if (firstDay == dayLabel(thread.lastMissedAt, now, zone, locale)) count else "$count since $firstDay"
}
