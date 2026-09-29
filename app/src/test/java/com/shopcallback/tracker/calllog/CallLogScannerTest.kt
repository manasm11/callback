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
        // Pin the clock just after the small fixed timestamps used below so none count as stale.
        scanner = CallLogScanner(dao, now = { 10_000L })
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

    @Test
    fun `missed calls older than 7 days are ignored`() = runBlocking {
        val scanner = CallLogScanner(dao, now = { NOW })
        scanner.applyNewEntries(
            listOf(
                CallLogEntry(30L, "9876543210", NOW - 8 * DAY, 0, CallDirection.MISSED),
                CallLogEntry(31L, "9123456789", NOW - 6 * DAY, 0, CallDirection.MISSED)
            )
        )

        assertNull(dao.findByNumber("9876543210"))
        assertEquals(CallbackStatus.PENDING, dao.findByNumber("9123456789")?.status)
    }

    @Test
    fun `a missed call after a week of silence starts a fresh callback`() = runBlocking {
        val scanner = CallLogScanner(dao, now = { NOW })
        scanner.applyNewEntries(listOf(CallLogEntry(40L, "9876543210", NOW - 6 * DAY, 0, CallDirection.MISSED)))

        val later = CallLogScanner(dao, now = { NOW + 2 * DAY })
        later.applyNewEntries(listOf(CallLogEntry(41L, "9876543210", NOW + 2 * DAY, 0, CallDirection.MISSED)))

        val thread = dao.findByNumber("9876543210")
        assertEquals(1, thread?.attemptCount)
        assertEquals(NOW + 2 * DAY, thread?.firstMissedAt)
    }

    @Test
    fun `dropStalePending removes only pending callbacks last missed over 7 days ago`() = runBlocking {
        val scanner = CallLogScanner(dao, now = { NOW - 10 * DAY })
        scanner.applyNewEntries(
            listOf(
                CallLogEntry(50L, "9000000001", NOW - 10 * DAY, 0, CallDirection.MISSED),
                CallLogEntry(51L, "9000000002", NOW - 10 * DAY, 0, CallDirection.MISSED),
                CallLogEntry(52L, "9000000002", NOW - 10 * DAY + 60_000, 30, CallDirection.OUTGOING)
            )
        )
        CallLogScanner(dao, now = { NOW - DAY })
            .applyNewEntries(listOf(CallLogEntry(53L, "9000000003", NOW - DAY, 0, CallDirection.MISSED)))

        CallLogScanner(dao, now = { NOW }).dropStalePending()

        assertNull(dao.findByNumber("9000000001"))
        assertEquals(CallbackStatus.RESOLVED, dao.findByNumber("9000000002")?.status)
        assertEquals(CallbackStatus.PENDING, dao.findByNumber("9000000003")?.status)
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val NOW = 100 * DAY
    }
}
