package com.shopcallback.tracker.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

data class DayGroup<T>(val epochDay: Long, val label: String, val items: List<T>)

/** e.g. "Today · 3". Shared by the in-app day headers and the widget. */
fun DayGroup<*>.headerText(): String = "$label · ${items.size}"

/** Splits [items] by local calendar day, newest day first and newest item first within each day. */
fun <T> groupByDayNewestFirst(
    items: List<T>,
    timestampOf: (T) -> Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): List<DayGroup<T>> =
    items.sortedByDescending(timestampOf)
        .groupBy { localDate(timestampOf(it), zone) }
        .map { (date, dayItems) -> DayGroup(date.toEpochDay(), dayLabel(date, now, zone, locale), dayItems) }

/** "Today", "Yesterday", a weekday name within the past week, otherwise e.g. "Wed, 23 Sep". */
fun dayLabel(
    timestamp: Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String = dayLabel(localDate(timestamp, zone), now, zone, locale)

private fun dayLabel(date: LocalDate, now: Long, zone: ZoneId, locale: Locale): String {
    val daysAgo = ChronoUnit.DAYS.between(date, localDate(now, zone))
    return when {
        daysAgo == 0L -> "Today"
        daysAgo == 1L -> "Yesterday"
        daysAgo in 2..6 -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> date.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))
    }
}

private fun localDate(timestamp: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
