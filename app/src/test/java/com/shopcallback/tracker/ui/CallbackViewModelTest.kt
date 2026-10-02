package com.shopcallback.tracker.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.CallbackThreadEntity
import com.shopcallback.tracker.data.ResolvedReason
import com.shopcallback.tracker.data.SyncEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class CallbackViewModelTest {
    private lateinit var db: CallbackDatabase
    private lateinit var dao: CallbackThreadDao
    private lateinit var viewModel: CallbackViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val directExecutor = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallbackDatabase::class.java
        ).allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        dao = db.callbackThreadDao()
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        viewModel = CallbackViewModel(application, dao, syncDao = db.syncEventDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `pendingThreads reflects dao state`() {
        runBlocking {
            dao.upsert(thread("111", CallbackStatus.PENDING))
            dao.upsert(thread("222", CallbackStatus.RESOLVED))
        }

        val collected = mutableListOf<List<CallbackThreadEntity>>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.pendingThreads.collect { collected.add(it) }
        }

        assertEquals(listOf("111"), collected.last().map { it.phoneNumber })
        job.cancel()
    }

    @Test
    fun `callBackIntent builds ACTION_CALL with a tel uri`() {
        val intent = viewModel.callBackIntent("9876543210")
        assertEquals(android.content.Intent.ACTION_CALL, intent.action)
        assertEquals("tel:9876543210", intent.data.toString())
    }

    @Test
    fun `callBackIntent keeps a number with special characters whole`() {
        assertEquals("*123#", viewModel.callBackIntent("*123#").data!!.schemeSpecificPart)
    }

    @Test
    fun `markResolvedManually updates the dao`() {
        runBlocking { dao.upsert(thread("333", CallbackStatus.PENDING)) }
        viewModel.markResolvedManually("333")
        val updated = runBlocking { dao.findByNumber("333") }
        assertEquals(CallbackStatus.RESOLVED, updated?.status)
        assertEquals(ResolvedReason.MANUAL, updated?.resolvedReason)
    }

    @Test
    fun `unresolve moves a resolved thread back to pending`() {
        runBlocking { dao.upsert(thread("444", CallbackStatus.RESOLVED)) }
        viewModel.unresolve("444")
        val updated = runBlocking { dao.findByNumber("444") }
        assertEquals(CallbackStatus.PENDING, updated?.status)
        assertEquals(null, updated?.resolvedReason)
    }

    @Test
    fun `opening the app drops pending callbacks last missed over 7 days ago`() {
        val day = 24 * 60 * 60 * 1000L
        val now = 100 * day
        runBlocking {
            dao.upsert(thread("555", CallbackStatus.PENDING).copy(firstMissedAt = now - 9 * day, lastMissedAt = now - 8 * day))
            dao.upsert(thread("666", CallbackStatus.PENDING).copy(firstMissedAt = now - 9 * day, lastMissedAt = now - 2 * day))
        }

        CallbackViewModel(ApplicationProvider.getApplicationContext(), dao, now = { now }, syncDao = db.syncEventDao())

        assertNull(runBlocking { dao.findByNumber("555") })
        assertEquals(CallbackStatus.PENDING, runBlocking { dao.findByNumber("666") }?.status)
    }

    @Test
    fun `manual actions are queued for the other phones`() {
        runBlocking { dao.upsert(thread("777", CallbackStatus.PENDING)) }

        viewModel.markResolvedManually("777")
        viewModel.unresolve("777")

        val queued = runBlocking { db.syncEventDao().outboxBatch(10) }
        assertEquals(
            listOf(SyncEventType.MANUAL_RESOLVE, SyncEventType.UNRESOLVE).toSet(),
            queued.map { it.type }.toSet()
        )
        assertEquals(setOf("777"), queued.map { it.number }.toSet())
    }

    @Test
    fun `unresolve records when it happened`() {
        runBlocking { dao.upsert(thread("888", CallbackStatus.RESOLVED)) }
        val fixedNow = CallbackViewModel(
            ApplicationProvider.getApplicationContext(), dao, now = { 5_000L }, syncDao = db.syncEventDao()
        )

        fixedNow.unresolve("888")

        assertEquals(5_000L, runBlocking { dao.findByNumber("888") }?.reopenedAt)
    }

    private fun thread(number: String, status: CallbackStatus) = CallbackThreadEntity(
        phoneNumber = number, displayName = null, firstMissedAt = 1L, lastMissedAt = 1L,
        attemptCount = 1, status = status,
        resolvedAt = if (status == CallbackStatus.RESOLVED) 2L else null,
        resolvedReason = if (status == CallbackStatus.RESOLVED) ResolvedReason.MANUAL else null
    )
}
