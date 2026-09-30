package com.shopcallback.tracker.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadEntity
import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.data.ResolvedReason
import com.shopcallback.tracker.data.SyncEventType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CallbackDatabase
    private lateinit var server: FakeSyncServer
    private lateinit var settings: SyncSettings
    private var recentCalls = emptyList<OutboxEventEntity>()
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, CallbackDatabase::class.java).allowMainThreadQueries().build()
        server = FakeSyncServer()
        settings = SyncSettings(context)
        engine = SyncEngine(
            settings = settings,
            syncDao = db.syncEventDao(),
            applier = RemoteEventApplier(db.callbackThreadDao(), db.syncEventDao()),
            recentCallEvents = { recentCalls },
            now = { NOW }
        )
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun `with sync off nothing is sent and the outbox is kept`() = runBlocking {
        db.syncEventDao().enqueue(listOf(outboxCall("mine:call:1")))

        assertFalse(engine.syncOnce())

        assertEquals(1, db.syncEventDao().outboxBatch(10).size)
        assertTrue(server.events.isEmpty())
    }

    @Test
    fun `turning sync on shares the past week's calls`() = runBlocking {
        recentCalls = listOf(outboxCall("mine:call:7"))

        engine.onServerUrlChanged("  ${server.url}  ")

        assertEquals(server.url, settings.serverUrl)
        assertEquals(listOf("mine:call:7"), db.syncEventDao().outboxBatch(10).map { it.eventId })
    }

    @Test
    fun `a pass uploads the outbox, clears it and records the time`() = runBlocking {
        engine.onServerUrlChanged(server.url)
        db.syncEventDao().enqueue(listOf(outboxCall("mine:call:1")))

        assertTrue(engine.syncOnce())

        assertTrue(db.syncEventDao().outboxBatch(10).isEmpty())
        synchronized(server.events) {
            assertEquals(listOf("mine:call:1"), server.events.map { it.getString("eventId") })
            assertEquals(settings.deviceId, server.events.single().getString("deviceId"))
        }
        assertEquals(NOW, settings.lastSyncAt)
        assertEquals("server-1", settings.serverId)
    }

    @Test
    fun `a call made on another phone resolves the callback here`() = runBlocking {
        engine.onServerUrlChanged(server.url)
        db.callbackThreadDao().upsert(pending(firstMissedAt = NOW - 60_000))
        server.addFromOtherPhone("other:call:1", "CALL", NUMBER, NOW - 30_000, 40, "OUTGOING")

        assertTrue(engine.syncOnce())

        val thread = db.callbackThreadDao().findByNumber(NUMBER)
        assertEquals(CallbackStatus.RESOLVED, thread?.status)
        assertEquals(ResolvedReason.REMOTE_ANSWERED, thread?.resolvedReason)
        assertEquals(1L, settings.cursor)
    }

    @Test
    fun `pulls every page`() = runBlocking {
        engine.onServerUrlChanged(server.url)
        repeat(SyncEngine.PULL_PAGE_SIZE + 1) {
            server.addFromOtherPhone("other:call:$it", "CALL", "9000000${1000 + it}", NOW - 1_000, 40, "OUTGOING")
        }

        assertTrue(engine.syncOnce())

        assertEquals(SyncEngine.PULL_PAGE_SIZE + 1L, settings.cursor)
    }

    @Test
    fun `a rejected batch is dropped so it cannot block the queue`() = runBlocking {
        engine.onServerUrlChanged(server.url)
        db.syncEventDao().enqueue(listOf(outboxCall("mine:call:1")))
        server.rejectUploads = true

        assertTrue(engine.syncOnce())

        assertTrue(db.syncEventDao().outboxBatch(10).isEmpty())
    }

    @Test
    fun `an unreachable server keeps the outbox for next time`() = runBlocking {
        val deadUrl = FakeSyncServer().run { close(); url }
        engine.onServerUrlChanged(deadUrl)
        db.syncEventDao().enqueue(listOf(outboxCall("mine:call:1")))

        assertFalse(engine.syncOnce())

        assertEquals(1, db.syncEventDao().outboxBatch(10).size)
        assertNull(settings.lastSyncAt)
    }

    @Test
    fun `a reset server gets the past week's calls again and is pulled from the start`() = runBlocking {
        engine.onServerUrlChanged(server.url)
        server.addFromOtherPhone("other:call:1", "CALL", NUMBER, NOW - 30_000, 40, "OUTGOING")
        assertTrue(engine.syncOnce())
        assertEquals(1L, settings.cursor)

        // The server's database is wiped: new ID, no events.
        synchronized(server.events) { server.events.clear() }
        server.serverId = "server-2"
        recentCalls = listOf(outboxCall("mine:call:9"))

        assertTrue(engine.syncOnce())

        assertEquals("server-2", settings.serverId)
        synchronized(server.events) {
            assertEquals(listOf("mine:call:9"), server.events.map { it.getString("eventId") })
        }
        assertEquals(0L, settings.cursor)
        assertTrue(db.syncEventDao().outboxBatch(10).isEmpty())
    }

    @Test
    fun `old outbox events are purged even with sync off`() = runBlocking {
        db.syncEventDao().enqueue(listOf(outboxCall("old", timestamp = NOW - 8 * DAY)))

        engine.syncOnce()

        assertTrue(db.syncEventDao().outboxBatch(10).isEmpty())
    }

    private fun outboxCall(id: String, timestamp: Long = NOW - 1_000) =
        OutboxEventEntity(id, SyncEventType.CALL, NUMBER, timestamp, 40, "OUTGOING")

    private fun pending(firstMissedAt: Long) = CallbackThreadEntity(
        phoneNumber = NUMBER, displayName = null, firstMissedAt = firstMissedAt, lastMissedAt = firstMissedAt,
        attemptCount = 1, status = CallbackStatus.PENDING, resolvedAt = null, resolvedReason = null
    )

    companion object {
        private const val NUMBER = "9876543210"
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val NOW = 100 * DAY
    }
}
