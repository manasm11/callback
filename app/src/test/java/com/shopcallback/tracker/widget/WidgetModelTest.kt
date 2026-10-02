package com.shopcallback.tracker.widget

import android.content.Intent
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class WidgetModelTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    // Wednesday 30 Sep 2026, 3:00 PM local time.
    private val now = at(2026, 9, 30, 15, 0)

    private val clock: (Long) -> String = { ts ->
        val t = java.time.Instant.ofEpochMilli(ts).atZone(zone)
        "%02d:%02d".format(t.hour, t.minute)
    }

    @Test
    fun `groups pending callbacks by latest missed call, newest day and caller first`() {
        val model = buildWidgetModel(
            listOf(
                thread("9000000001", "Priya", first = at(2026, 9, 28, 9, 0), last = at(2026, 9, 28, 9, 0)),
                thread("9000000002", null, first = at(2026, 9, 30, 8, 0), last = at(2026, 9, 30, 8, 0)),
                thread("9000000003", "Ravi", first = at(2026, 9, 28, 10, 0), last = at(2026, 9, 30, 14, 0), attempts = 3)
            ),
            now, clock, zone, Locale.US
        )

        assertEquals(3, model.total)
        assertEquals(listOf("Today · 2", "Monday · 1"), model.sections.map { it.header })
        assertEquals(
            listOf(
                WidgetRow("Ravi", "14:00 · 3 missed calls since Monday", "9000000003"),
                WidgetRow("9000000002", "08:00 · 1 missed call", "9000000002")
            ),
            model.sections[0].rows
        )
        assertEquals(listOf(WidgetRow("Priya", "09:00 · 1 missed call", "9000000001")), model.sections[1].rows)
    }

    @Test
    fun `no pending callbacks gives an empty model`() {
        assertEquals(WidgetModel(total = 0, sections = emptyList()), buildWidgetModel(emptyList(), now, clock, zone, Locale.US))
    }

    @Test
    fun `tapping calls directly when allowed, otherwise opens the dialer`() {
        val call = callBackIntent("9876543210", canCall = true)
        assertEquals(Intent.ACTION_CALL, call.action)
        assertEquals("tel:9876543210", call.data.toString())

        val dial = callBackIntent("9876543210", canCall = false)
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:9876543210", dial.data.toString())
    }

    @Test
    fun `numbers with special characters keep their whole value`() {
        val intent = callBackIntent("*123#", canCall = false)
        assertEquals("*123#", intent.data!!.schemeSpecificPart)
    }

    private fun thread(number: String, name: String?, first: Long, last: Long, attempts: Int = 1) = CallbackThreadEntity(
        phoneNumber = number, displayName = name, firstMissedAt = first, lastMissedAt = last,
        attemptCount = attempts, status = CallbackStatus.PENDING, resolvedAt = null, resolvedReason = null
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
}
