package com.shopcallback.tracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class DayGroupingTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    // Wednesday 30 Sep 2026, 3:00 PM local time.
    private val now = at(2026, 9, 30, 15, 0)

    @Test
    fun `labels today, yesterday, weekdays within the week and dates beyond it`() {
        assertEquals("Today", label(at(2026, 9, 30, 0, 5)))
        assertEquals("Yesterday", label(at(2026, 9, 29, 23, 59)))
        assertEquals("Monday", label(at(2026, 9, 28, 9, 0)))
        assertEquals("Thursday", label(at(2026, 9, 24, 9, 0)))
        assertEquals("Wed, 23 Sep", label(at(2026, 9, 23, 9, 0)))
    }

    @Test
    fun `groups by local calendar day, newest day first and newest item first within a day`() {
        val items = listOf(
            "mon-morning" to at(2026, 9, 28, 9, 0),
            "today-early" to at(2026, 9, 30, 8, 0),
            "mon-evening" to at(2026, 9, 28, 19, 0),
            "today-late" to at(2026, 9, 30, 14, 0),
        )

        val groups = groupByDayNewestFirst(items, { it.second }, now, zone, Locale.US)

        assertEquals(listOf("Today", "Monday"), groups.map { it.label })
        assertEquals(listOf("today-late", "today-early"), groups[0].items.map { it.first })
        assertEquals(listOf("mon-evening", "mon-morning"), groups[1].items.map { it.first })
    }

    @Test
    fun `no items gives no groups`() {
        assertEquals(emptyList<DayGroup<String>>(), groupByDayNewestFirst(emptyList<String>(), { 0L }, now, zone, Locale.US))
    }

    private fun label(timestamp: Long) = dayLabel(timestamp, now, zone, Locale.US)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
}
