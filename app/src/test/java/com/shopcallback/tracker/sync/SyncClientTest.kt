package com.shopcallback.tracker.sync

import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.data.RemoteEventEntity
import com.shopcallback.tracker.data.SyncEventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SyncClientTest {
    private val server = FakeSyncServer()
    private val client = SyncClient(server.url + "/")

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `health returns the server id`() {
        assertEquals("server-1", client.health())
    }

    @Test
    fun `uploaded events reach other phones with every field intact`() {
        val call = OutboxEventEntity("phone-a:call:1", SyncEventType.CALL, "9876543210", 1000L, 30, "OUTGOING")
        val manual = OutboxEventEntity("phone-a:manual:x", SyncEventType.UNRESOLVE, "9876543210", 2000L)

        assertEquals(SyncClient.UploadResult.Accepted("server-1"), client.upload("phone-a", listOf(call, manual)))

        val pull = client.pull("phone-b", after = 0L)
        assertEquals("server-1", pull.serverId)
        assertEquals(2L, pull.latestSeq)
        assertEquals(
            listOf(
                SyncClient.PulledEvent(1L, RemoteEventEntity("phone-a:call:1", SyncEventType.CALL, "9876543210", 1000L, 30, "OUTGOING")),
                SyncClient.PulledEvent(2L, RemoteEventEntity("phone-a:manual:x", SyncEventType.UNRESOLVE, "9876543210", 2000L))
            ),
            pull.events
        )
    }

    @Test
    fun `a phone does not pull its own events`() {
        client.upload("phone-a", listOf(OutboxEventEntity("phone-a:call:1", SyncEventType.CALL, "9876543210", 1000L, 30, "OUTGOING")))
        assertEquals(emptyList<SyncClient.PulledEvent>(), client.pull("phone-a", after = 0L).events)
    }

    @Test
    fun `events of an unknown type are skipped but still count toward the page`() {
        server.addFromOtherPhone("other:future:1", "SOME_FUTURE_TYPE", "9876543210", 1000L)
        server.addFromOtherPhone("other:manual:1", "MANUAL_RESOLVE", "9876543210", 2000L)
        server.addFromOtherPhone("other:future:2", "SOME_FUTURE_TYPE", "9876543210", 3000L)

        val pull = client.pull("phone-b", after = 0L)

        assertEquals(
            listOf(SyncClient.PulledEvent(2L, RemoteEventEntity("other:manual:1", SyncEventType.MANUAL_RESOLVE, "9876543210", 2000L))),
            pull.events
        )
        assertEquals(3L, pull.lastSeq)
        assertEquals(3, pull.pageSize)
    }

    @Test
    fun `an empty page has no last seq`() {
        val pull = client.pull("phone-b", after = 0L)
        assertEquals(null, pull.lastSeq)
        assertEquals(0, pull.pageSize)
    }

    @Test
    fun `a rejected upload is reported, not thrown`() {
        server.rejectUploads = true
        assertEquals(SyncClient.UploadResult.Rejected, client.upload("phone-a", emptyList()))
    }

    @Test(expected = IOException::class)
    fun `an unreachable server throws IOException`() {
        val url = FakeSyncServer().run { close(); url }
        SyncClient(url).health()
    }
}
