package com.shopcallback.tracker.service

import android.Manifest
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.calllog.CallDirection
import com.shopcallback.tracker.calllog.CallLogEntry
import com.shopcallback.tracker.calllog.CallLogSource
import com.shopcallback.tracker.contacts.ContactLookup
import com.shopcallback.tracker.data.CallbackDatabase
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.first
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
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.READ_CALL_LOG)
    }

    @After
    fun tearDown() {
        CallWatcherService.testCallLogSource = null
        CallWatcherService.testDispatcher = null
        CallWatcherService.testDatabase = null
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
        val service = Robolectric.buildService(CallWatcherService::class.java).create().get()

        val result = service.onStartCommand(null, 0, 0)
        assertEquals(android.app.Service.START_STICKY, result)
    }
}
