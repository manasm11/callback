package com.shopcallback.tracker.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.CallbackThreadEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class PendingCallbacksScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: CallbackDatabase
    private lateinit var dao: CallbackThreadDao
    private lateinit var viewModel: CallbackViewModel

    @Before
    fun setUp() {
        val directExecutor = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallbackDatabase::class.java
        ).allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        dao = db.callbackThreadDao()
        val recent = System.currentTimeMillis()
        runBlocking {
            dao.upsert(
                CallbackThreadEntity(
                    phoneNumber = "9876543210", displayName = "Priya", firstMissedAt = recent, lastMissedAt = recent,
                    attemptCount = 1, status = CallbackStatus.PENDING, resolvedAt = null, resolvedReason = null
                )
            )
        }
        viewModel = CallbackViewModel(ApplicationProvider.getApplicationContext(), dao, syncDao = db.syncEventDao())
        composeRule.setContent { PendingCallbacksScreen(viewModel) {} }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `callbacks are listed under a day header`() {
        composeRule.onNodeWithText("Today · 1").assertIsDisplayed()
    }

    @Test
    fun `mark resolved asks for confirmation before resolving`() {
        composeRule.onNodeWithText("Mark resolved").performClick()

        composeRule.onNodeWithText("Mark Priya as resolved?").assertIsDisplayed()
        assertEquals(CallbackStatus.PENDING, status())
    }

    @Test
    fun `cancelling the confirmation leaves the callback pending`() {
        composeRule.onNodeWithText("Mark resolved").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Mark Priya as resolved?").assertDoesNotExist()
        assertEquals(CallbackStatus.PENDING, status())
    }

    @Test
    fun `confirming resolves the callback`() {
        composeRule.onNodeWithText("Mark resolved").performClick()
        composeRule.onNodeWithText("Resolve").performClick()
        composeRule.waitForIdle()

        assertEquals(CallbackStatus.RESOLVED, status())
    }

    private fun status() = runBlocking { dao.findByNumber("9876543210")?.status }
}
