package com.shopcallback.tracker.ui

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.service.CallWatcherService
import com.shopcallback.tracker.sync.FakeSyncServer
import com.shopcallback.tracker.sync.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: CallbackDatabase
    private lateinit var server: FakeSyncServer
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(application, CallbackDatabase::class.java).allowMainThreadQueries().build()
        server = FakeSyncServer()
        SyncSettings(application).serverUrl = "http://saved:8787"
        viewModel = SettingsViewModel(application, SyncSettings(application), db.syncEventDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.close()
        db.close()
    }

    @Test
    fun `shows the saved address`() {
        assertEquals("http://saved:8787", viewModel.savedServerUrl)
    }

    @Test
    fun `test connection reports a reachable server`() = runBlocking {
        viewModel.testConnection(server.url)
        val result = withTimeout(5_000) { viewModel.connection.first { it != null && it != "Checking…" } }
        assertEquals("Connected ✓", result)
    }

    @Test
    fun `test connection reports an unreachable server`() = runBlocking {
        val deadUrl = FakeSyncServer().run { close(); url }
        viewModel.testConnection(deadUrl)
        val result = withTimeout(15_000) { viewModel.connection.first { it != null && it != "Checking…" } }
        assertEquals("Can't reach server", result)
    }

    @Test
    fun `test connection adds a missing http scheme`() = runBlocking {
        viewModel.testConnection(server.url.removePrefix("http://"))
        val result = withTimeout(5_000) { viewModel.connection.first { it != null && it != "Checking…" } }
        assertEquals("Connected ✓", result)
    }

    @Test
    fun `saving adds a missing http scheme`() {
        viewModel.save(" shop-pc:8787 ")

        val started = shadowOf(application).nextStartedService
        assertEquals("http://shop-pc:8787", started.getStringExtra(CallWatcherService.EXTRA_SERVER_URL))
    }

    @Test
    fun `saving hands the trimmed address to the service`() {
        viewModel.save("  http://shop-pc:8787  ")

        val started = shadowOf(application).nextStartedService
        assertEquals(CallWatcherService.ACTION_SERVER_CHANGED, started.action)
        assertEquals("http://shop-pc:8787", started.getStringExtra(CallWatcherService.EXTRA_SERVER_URL))
    }
}
