package com.shopcallback.tracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncEventDaoTest {
    private lateinit var db: CallbackDatabase
    private lateinit var dao: SyncEventDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallbackDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.syncEventDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `outbox returns oldest first, ignores duplicates and deletes by id`() = runBlocking {
        dao.enqueue(listOf(outbox("b", 200L), outbox("a", 100L)))
        dao.enqueue(listOf(outbox("a", 999L)))

        assertEquals(listOf("a", "b"), dao.outboxBatch(10).map { it.eventId })
        assertEquals(100L, dao.outboxBatch(10).first().timestamp)
        assertEquals(listOf("a"), dao.outboxBatch(1).map { it.eventId })
        assertEquals(2, dao.observeOutboxCount().first())

        dao.deleteOutbox(listOf("a"))
        assertEquals(listOf("b"), dao.outboxBatch(10).map { it.eventId })
    }

    @Test
    fun `old outbox and remote events are purged`() = runBlocking {
        dao.enqueue(listOf(outbox("old", 100L), outbox("new", 500L)))
        dao.insertRemote(listOf(remoteCall("old", 100L), remoteCall("new", 500L)))

        dao.deleteOutboxBefore(300L)
        dao.deleteRemoteBefore(300L)

        assertEquals(listOf("new"), dao.outboxBatch(10).map { it.eventId })
        assertEquals(500L, dao.earliestRemoteCallAfter("9876543210", 0L))
    }

    @Test
    fun `earliestRemoteCallAfter only counts calls over 1 second after the given time`() = runBlocking {
        dao.insertRemote(
            listOf(
                remoteCall("before", 100L),
                remoteCall("short", 300L, durationSeconds = 1),
                remoteCall("other-number", 350L, number = "9123456789"),
                remoteCall("match", 400L),
                remoteCall("later", 900L),
                RemoteEventEntity("manual", SyncEventType.MANUAL_RESOLVE, "9876543210", 250L)
            )
        )

        assertEquals(400L, dao.earliestRemoteCallAfter("9876543210", 200L))
        assertNull(dao.earliestRemoteCallAfter("9876543210", 900L))
    }

    @Test
    fun `unapplied manual events come oldest first until marked applied`() = runBlocking {
        dao.insertRemote(
            listOf(
                RemoteEventEntity("u", SyncEventType.UNRESOLVE, "9876543210", 300L),
                RemoteEventEntity("m", SyncEventType.MANUAL_RESOLVE, "9876543210", 200L),
                remoteCall("c", 100L)
            )
        )
        dao.insertRemote(listOf(RemoteEventEntity("m", SyncEventType.MANUAL_RESOLVE, "9876543210", 999L)))

        assertEquals(listOf("m", "u"), dao.unappliedManualEvents().map { it.eventId })
        dao.markApplied(listOf("m"))
        assertEquals(listOf("u"), dao.unappliedManualEvents().map { it.eventId })
    }

    private fun outbox(id: String, timestamp: Long) =
        OutboxEventEntity(id, SyncEventType.CALL, "9876543210", timestamp, 30, "OUTGOING")

    private fun remoteCall(id: String, timestamp: Long, durationSeconds: Int = 30, number: String = "9876543210") =
        RemoteEventEntity(id, SyncEventType.CALL, number, timestamp, durationSeconds, "OUTGOING")
}
