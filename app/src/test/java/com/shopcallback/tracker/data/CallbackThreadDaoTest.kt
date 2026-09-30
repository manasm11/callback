package com.shopcallback.tracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallbackThreadDaoTest {
    private lateinit var db: CallbackDatabase
    private lateinit var dao: CallbackThreadDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallbackDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.callbackThreadDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and find by number`() = runBlocking {
        val thread = pendingThread("9876543210")
        dao.upsert(thread)
        assertEquals(thread, dao.findByNumber("9876543210"))
    }

    @Test
    fun `observePending excludes resolved threads`() = runBlocking {
        dao.upsert(pendingThread("111"))
        dao.upsert(
            pendingThread("222").copy(
                status = CallbackStatus.RESOLVED,
                resolvedAt = 200L,
                resolvedReason = ResolvedReason.MANUAL
            )
        )

        val pending = dao.observePending().first()
        assertEquals(listOf("111"), pending.map { it.phoneNumber })
    }

    @Test
    fun `observeHistory returns only resolved threads newest first`() = runBlocking {
        dao.upsert(pendingThread("111"))
        dao.upsert(
            pendingThread("222").copy(
                status = CallbackStatus.RESOLVED, resolvedAt = 500L, resolvedReason = ResolvedReason.MANUAL
            )
        )
        dao.upsert(
            pendingThread("333").copy(
                status = CallbackStatus.RESOLVED, resolvedAt = 900L, resolvedReason = ResolvedReason.AUTO_ANSWERED
            )
        )

        val history = dao.observeHistory().first()
        assertEquals(listOf("333", "222"), history.map { it.phoneNumber })
    }

    @Test
    fun `markResolved updates status, timestamp and reason`() = runBlocking {
        dao.upsert(pendingThread("333"))
        dao.markResolved("333", CallbackStatus.RESOLVED, 500L, ResolvedReason.AUTO_ANSWERED)

        val updated = dao.findByNumber("333")
        assertEquals(CallbackStatus.RESOLVED, updated?.status)
        assertEquals(500L, updated?.resolvedAt)
        assertEquals(ResolvedReason.AUTO_ANSWERED, updated?.resolvedReason)
    }

    @Test
    fun `reopen moves a resolved thread back to pending, clears resolution and records when`() = runBlocking {
        dao.upsert(pendingThread("555").copy(attemptCount = 3))
        dao.markResolved("555", CallbackStatus.RESOLVED, 500L, ResolvedReason.MANUAL)

        dao.reopen("555", 700L)

        val reopened = dao.findByNumber("555")
        assertEquals(CallbackStatus.PENDING, reopened?.status)
        assertEquals(null, reopened?.resolvedAt)
        assertEquals(null, reopened?.resolvedReason)
        assertEquals(700L, reopened?.reopenedAt)
        assertEquals(3, reopened?.attemptCount)
        assertEquals(listOf("555"), dao.observePending().first().map { it.phoneNumber })
        assertEquals(emptyList<String>(), dao.observeHistory().first().map { it.phoneNumber })
    }

    @Test
    fun `updateDisplayNameIfMissing only fills a null name`() = runBlocking {
        dao.upsert(pendingThread("444"))
        dao.updateDisplayNameIfMissing("444", "Priya")
        assertEquals("Priya", dao.findByNumber("444")?.displayName)

        dao.updateDisplayNameIfMissing("444", "Someone Else")
        assertEquals("Priya", dao.findByNumber("444")?.displayName)
    }

    private fun pendingThread(number: String) = CallbackThreadEntity(
        phoneNumber = number,
        displayName = null,
        firstMissedAt = 100L,
        lastMissedAt = 100L,
        attemptCount = 1,
        status = CallbackStatus.PENDING,
        resolvedAt = null,
        resolvedReason = null
    )
}
