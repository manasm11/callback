package com.shopcallback.tracker.sync

import com.shopcallback.tracker.calllog.CallDirection
import com.shopcallback.tracker.calllog.CallLogEntry
import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.data.SyncEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxEventsTest {
    @Test
    fun `only answered or outgoing calls over 1 second with a real number become events`() {
        val events = OutboxEvents.forCalls(
            "phone-a",
            listOf(
                CallLogEntry(1L, "+91 98765 43210", 1000L, 30, CallDirection.OUTGOING),
                CallLogEntry(2L, "9876543210", 2000L, 45, CallDirection.INCOMING),
                CallLogEntry(3L, "9876543210", 3000L, 0, CallDirection.MISSED),
                CallLogEntry(4L, "9876543210", 4000L, 1, CallDirection.OUTGOING),
                CallLogEntry(5L, "-1", 5000L, 60, CallDirection.INCOMING)
            )
        )

        assertEquals(
            listOf(
                OutboxEventEntity("phone-a:call:1", SyncEventType.CALL, "9876543210", 1000L, 30, "OUTGOING"),
                OutboxEventEntity("phone-a:call:2", SyncEventType.CALL, "9876543210", 2000L, 45, "INCOMING")
            ),
            events
        )
    }

    @Test
    fun `manual events get a unique id and no call fields`() {
        val first = OutboxEvents.manual("phone-a", SyncEventType.MANUAL_RESOLVE, "9876543210", 500L)
        val second = OutboxEvents.manual("phone-a", SyncEventType.MANUAL_RESOLVE, "9876543210", 500L)

        assertTrue(first.eventId.startsWith("phone-a:manual:"))
        assertNotEquals(first.eventId, second.eventId)
        assertEquals(SyncEventType.MANUAL_RESOLVE, first.type)
        assertEquals(500L, first.timestamp)
        assertNull(first.durationSeconds)
        assertNull(first.direction)
    }
}
