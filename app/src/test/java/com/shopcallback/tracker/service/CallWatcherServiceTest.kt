package com.shopcallback.tracker.service

import android.Manifest
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.calllog.CallDirection
import com.shopcallback.tracker.calllog.CallLogEntry
import com.shopcallback.tracker.calllog.CallLogSource
import com.shopcallback.tracker.contacts.ContactLookup
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.RemoteEventEntity
import com.shopcallback.tracker.data.ResolvedReason
import com.shopcallback.tracker.data.SyncEventType
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CallWatcherServiceTest {

    private lateinit var testDb: CallbackDatabase

    private fun freshTestDatabase(): CallbackDatabase {
        val directExecutor = Executor { it.run() }
        return Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallbackDatabase::class.java
        ).allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
    }

    @Before
    fun grantCallLogPermission() {
        CallWatcherService.testDisablePolling = true
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.READ_CALL_LOG)
    }

    @After
    fun tearDown() {
        CallWatcherService.testCallLogSource = null
        CallWatcherService.testDispatcher = null
        CallWatcherService.testDatabase = null
        CallWatcherService.testDisablePolling = false
        if (::testDb.isInitialized) testDb.close()
    }

    private fun fakeSource(entries: List<CallLogEntry>) = object : CallLogSource {
        override fun queryEntries(afterId: Long, afterDateMillis: Long) = entries
    }

    @Test
    fun `startup scan creates a pending thread from the fake call log source`() = runTest {
        testDb = freshTestDatabase()
        CallWatcherService.testDatabase = testDb
        CallWatcherService.testDispatcher = StandardTestDispatcher(testScheduler)
        CallWatcherService.testCallLogSource =
            fakeSource(listOf(CallLogEntry(1L, "9876543210", System.currentTimeMillis(), 0, CallDirection.MISSED)))

        val service = Robolectric.buildService(CallWatcherService::class.java).create().get()
        advanceUntilIdle()

        val pending = testDb.callbackThreadDao().observePending().first()
        assertEquals(1, pending.size)
        assertEquals("9876543210", pending[0].phoneNumber)
        assertEquals(service.javaClass, CallWatcherService::class.java)
    }

    @Test
    fun `onStartCommand returns START_STICKY`() {
        testDb = freshTestDatabase()
        CallWatcherService.testDatabase = testDb
        CallWatcherService.testCallLogSource = fakeSource(emptyList())
        CallWatcherService.testDispatcher = StandardTestDispatcher()
        val service = Robolectric.buildService(CallWatcherService::class.java).create().get()

        val result = service.onStartCommand(null, 0, 0)
        assertEquals(android.app.Service.START_STICKY, result)
    }

    @Test
    fun `scan queues answered calls for the other phones`() = runTest {
        testDb = freshTestDatabase()
        CallWatcherService.testDatabase = testDb
        CallWatcherService.testDispatcher = StandardTestDispatcher(testScheduler)
        CallWatcherService.testCallLogSource = fakeSource(
            listOf(
                CallLogEntry(1L, "9876543210", System.currentTimeMillis() - 60_000, 0, CallDirection.MISSED),
                CallLogEntry(2L, "9123456789", System.currentTimeMillis(), 30, CallDirection.OUTGOING)
            )
        )

        Robolectric.buildService(CallWatcherService::class.java).create().get()
        advanceUntilIdle()

        val queued = testDb.syncEventDao().outboxBatch(10)
        assertEquals(1, queued.size)
        assertEquals("9123456789", queued.single().number)
        assertEquals(true, queued.single().eventId.endsWith(":call:2"))
    }

    @Test
    fun `scan resolves a callback already answered on another phone`() = runTest {
        testDb = freshTestDatabase()
        val now = System.currentTimeMillis()
        runBlocking {
            testDb.syncEventDao().insertRemote(
                listOf(RemoteEventEntity("other:call:1", SyncEventType.CALL, "9876543210", now - 1_000, 40, "OUTGOING"))
            )
        }
        CallWatcherService.testDatabase = testDb
        CallWatcherService.testDispatcher = StandardTestDispatcher(testScheduler)
        CallWatcherService.testCallLogSource =
            fakeSource(listOf(CallLogEntry(1L, "9876543210", now - 60_000, 0, CallDirection.MISSED)))

        Robolectric.buildService(CallWatcherService::class.java).create().get()
        advanceUntilIdle()

        val thread = testDb.callbackThreadDao().findByNumber("9876543210")
        assertEquals(CallbackStatus.RESOLVED, thread?.status)
        assertEquals(ResolvedReason.REMOTE_ANSWERED, thread?.resolvedReason)
    }
}
