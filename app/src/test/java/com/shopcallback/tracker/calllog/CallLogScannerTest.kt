package com.shopcallback.tracker.calllog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.ResolvedReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallLogScannerTest {
    private lateinit var db: CallbackDatabase
    private lateinit var dao: CallbackThreadDao
    private lateinit var scanner: CallLogScanner

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallbackDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.callbackThreadDao()
        scanner = CallLogScanner(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `single missed call creates a pending thread`() = runBlocking {
        scanner.applyNewEntries(
            listOf(CallLogEntry(1L, "9876543210", timestamp = 1000L, durationSeconds = 0, direction = CallDirection.MISSED))
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(CallbackStatus.PENDING, thread?.status)
        assertEquals(1, thread?.attemptCount)
        assertEquals(1000L, thread?.firstMissedAt)
    }

    @Test
    fun `second missed call from same number bumps attempt count`() = runBlocking {
        scanner.applyNewEntries(
            listOf(
                CallLogEntry(2L, "9876543210", 1000L, 0, CallDirection.MISSED),
                CallLogEntry(3L, "9876543210", 2000L, 0, CallDirection.MISSED)
            )
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(2, thread?.attemptCount)
        assertEquals(1000L, thread?.firstMissedAt)
        assertEquals(2000L, thread?.lastMissedAt)
    }

    @Test
    fun `answered call over 1 second after the missed call resolves it`() = runBlocking {
        scanner.applyNewEntries(
            listOf(
                CallLogEntry(4L, "9876543210", 1000L, 0, CallDirection.MISSED),
                CallLogEntry(5L, "9876543210", 2000L, 15, CallDirection.OUTGOING)
            )
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(CallbackStatus.RESOLVED, thread?.status)
        assertEquals(ResolvedReason.AUTO_ANSWERED, thread?.resolvedReason)
        assertEquals(2000L, thread?.resolvedAt)
    }

    @Test
    fun `answered call of 1 second or less does not resolve`() = runBlocking {
        scanner.applyNewEntries(
            listOf(
                CallLogEntry(6L, "9876543210", 1000L, 0, CallDirection.MISSED),
                CallLogEntry(7L, "9876543210", 2000L, 1, CallDirection.INCOMING)
            )
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(CallbackStatus.PENDING, thread?.status)
    }

    @Test
    fun `resolved thread reopens as a new episode on a later missed call`() = runBlocking {
        scanner.applyNewEntries(
            listOf(
                CallLogEntry(8L, "9876543210", 1000L, 0, CallDirection.MISSED),
                CallLogEntry(9L, "9876543210", 2000L, 15, CallDirection.OUTGOING),
                CallLogEntry(10L, "9876543210", 5000L, 0, CallDirection.MISSED)
            )
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(CallbackStatus.PENDING, thread?.status)
        assertEquals(1, thread?.attemptCount)
        assertEquals(5000L, thread?.firstMissedAt)
    }

    @Test
    fun `an answered call before the missed call does not resolve it`() = runBlocking {
        scanner.applyNewEntries(
            listOf(
                CallLogEntry(11L, "9876543210", 500L, 20, CallDirection.INCOMING),
                CallLogEntry(12L, "9876543210", 1000L, 0, CallDirection.MISSED)
            )
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(CallbackStatus.PENDING, thread?.status)
    }

    @Test
    fun `numbers are normalized so formatting differences match`() = runBlocking {
        scanner.applyNewEntries(
            listOf(
                CallLogEntry(13L, "+91 98765 43210", 1000L, 0, CallDirection.MISSED),
                CallLogEntry(14L, "9876543210", 2000L, 10, CallDirection.OUTGOING)
            )
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(CallbackStatus.RESOLVED, thread?.status)
    }

    @Test
    fun `withheld or unknown numbers are skipped instead of creating a bogus thread`() = runBlocking {
        scanner.applyNewEntries(
            listOf(
                CallLogEntry(20L, "-1", 1000L, 0, CallDirection.MISSED),
                CallLogEntry(21L, "", 1100L, 0, CallDirection.MISSED)
            )
        )

        assertNull(dao.findByNumber("1"))
        assertNull(dao.findByNumber(""))
    }
}
