package com.shopcallback.tracker.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.CallbackThreadEntity
import com.shopcallback.tracker.data.RemoteEventEntity
import com.shopcallback.tracker.data.ResolvedReason
import com.shopcallback.tracker.data.SyncEventDao
import com.shopcallback.tracker.data.SyncEventType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteEventApplierTest {
    private lateinit var db: CallbackDatabase
    private lateinit var threads: CallbackThreadDao
    private lateinit var events: SyncEventDao
    private lateinit var applier: RemoteEventApplier

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallbackDatabase::class.java
        ).allowMainThreadQueries().build()
        threads = db.callbackThreadDao()
        events = db.syncEventDao()
        applier = RemoteEventApplier(threads, events)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a call from another phone after the miss resolves the callback`() = runBlocking {
        threads.upsert(pending(firstMissedAt = 1000L))
        events.insertRemote(listOf(call("b:call:1", 2000L)))

        applier.apply()

        val thread = threads.findByNumber(NUMBER)
        assertEquals(CallbackStatus.RESOLVED, thread?.status)
        assertEquals(ResolvedReason.REMOTE_ANSWERED, thread?.resolvedReason)
        assertEquals(2000L, thread?.resolvedAt)
    }

    @Test
    fun `calls before the miss or of 1 second or less do not resolve it`() = runBlocking {
        threads.upsert(pending(firstMissedAt = 1000L))
        events.insertRemote(listOf(call("b:call:1", 500L), call("b:call:2", 2000L, durationSeconds = 1)))

        applier.apply()

        assertEquals(CallbackStatus.PENDING, threads.findByNumber(NUMBER)?.status)
    }

    @Test
    fun `a remote call pulled before the local miss was scanned still resolves it`() = runBlocking {
        events.insertRemote(listOf(call("b:call:1", 2000L)))
        applier.apply()

        threads.upsert(pending(firstMissedAt = 1000L))
        applier.apply()

        assertEquals(ResolvedReason.REMOTE_ANSWERED, threads.findByNumber(NUMBER)?.resolvedReason)
    }

    @Test
    fun `calls before an un-resolve do not resolve it again`() = runBlocking {
        threads.upsert(pending(firstMissedAt = 1000L).copy(reopenedAt = 3000L))
        events.insertRemote(listOf(call("b:call:1", 2000L)))

        applier.apply()
        assertEquals(CallbackStatus.PENDING, threads.findByNumber(NUMBER)?.status)

        events.insertRemote(listOf(call("b:call:2", 4000L)))
        applier.apply()
        assertEquals(CallbackStatus.RESOLVED, threads.findByNumber(NUMBER)?.status)
    }

    @Test
    fun `mark resolved on another phone resolves a callback that started before it`() = runBlocking {
        threads.upsert(pending(firstMissedAt = 1000L))
        events.insertRemote(listOf(manual("b:manual:1", SyncEventType.MANUAL_RESOLVE, 2000L)))

        applier.apply()

        val thread = threads.findByNumber(NUMBER)
        assertEquals(ResolvedReason.REMOTE_MANUAL, thread?.resolvedReason)
        assertEquals(2000L, thread?.resolvedAt)
    }

    @Test
    fun `mark resolved on another phone leaves a newer callback pending`() = runBlocking {
        threads.upsert(pending(firstMissedAt = 3000L))
        events.insertRemote(listOf(manual("b:manual:1", SyncEventType.MANUAL_RESOLVE, 2000L)))

        applier.apply()

        assertEquals(CallbackStatus.PENDING, threads.findByNumber(NUMBER)?.status)
    }

    @Test
    fun `un-resolve on another phone reopens a callback resolved before it`() = runBlocking {
        threads.upsert(resolved(resolvedAt = 2000L))
        events.insertRemote(listOf(manual("b:manual:1", SyncEventType.UNRESOLVE, 3000L)))

        applier.apply()

        val thread = threads.findByNumber(NUMBER)
        assertEquals(CallbackStatus.PENDING, thread?.status)
        assertEquals(3000L, thread?.reopenedAt)
    }

    @Test
    fun `un-resolve on another phone leaves a callback resolved after it`() = runBlocking {
        threads.upsert(resolved(resolvedAt = 4000L))
        events.insertRemote(listOf(manual("b:manual:1", SyncEventType.UNRESOLVE, 3000L)))

        applier.apply()

        assertEquals(CallbackStatus.RESOLVED, threads.findByNumber(NUMBER)?.status)
    }

    @Test
    fun `manual events are applied only once`() = runBlocking {
        threads.upsert(resolved(resolvedAt = 2000L))
        events.insertRemote(listOf(manual("b:manual:1", SyncEventType.UNRESOLVE, 3000L)))
        applier.apply()

        // Resolved again locally, earlier than the remote un-resolve's time; re-applying would reopen it.
        threads.markResolved(NUMBER, CallbackStatus.RESOLVED, 2500L, ResolvedReason.MANUAL)
        applier.apply()

        assertEquals(CallbackStatus.RESOLVED, threads.findByNumber(NUMBER)?.status)
    }

    private fun pending(firstMissedAt: Long) = CallbackThreadEntity(
        phoneNumber = NUMBER, displayName = null, firstMissedAt = firstMissedAt, lastMissedAt = firstMissedAt,
        attemptCount = 1, status = CallbackStatus.PENDING, resolvedAt = null, resolvedReason = null
    )

    private fun resolved(resolvedAt: Long) =
        pending(firstMissedAt = 1000L).copy(status = CallbackStatus.RESOLVED, resolvedAt = resolvedAt, resolvedReason = ResolvedReason.MANUAL)

    private fun call(id: String, timestamp: Long, durationSeconds: Int = 30) =
        RemoteEventEntity(id, SyncEventType.CALL, NUMBER, timestamp, durationSeconds, "OUTGOING")

    private fun manual(id: String, type: SyncEventType, timestamp: Long) =
        RemoteEventEntity(id, type, NUMBER, timestamp)

    companion object {
        private const val NUMBER = "9876543210"
    }
}
