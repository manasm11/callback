package com.shopcallback.tracker.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.SyncEventDao
import com.shopcallback.tracker.service.CallWatcherService
import com.shopcallback.tracker.sync.SyncClient
import com.shopcallback.tracker.sync.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    application: Application,
    private val settings: SyncSettings = SyncSettings(application),
    syncDao: SyncEventDao = CallbackDatabase.getInstance(application).syncEventDao(),
    private val clientFor: (String) -> SyncClient = ::SyncClient,
    private val now: () -> Long = System::currentTimeMillis
) : AndroidViewModel(application) {

    val savedServerUrl: String get() = settings.serverUrl

    /** Re-read every few seconds: the service updates the settings in the background. */
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(STATUS_REFRESH_MILLIS)
        }
    }

    val status: StateFlow<String> = combine(syncDao.observeOutboxCount(), ticker) { waiting, _ ->
        syncStatusText(settings.serverUrl, settings.lastSyncAt, waiting, now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _connection = MutableStateFlow<String?>(null)
    /** Result of the last Test connection, or null if none since the last save. */
    val connection: StateFlow<String?> = _connection

    fun testConnection(url: String) {
        _connection.value = "Checking…"
        viewModelScope.launch {
            val reachable = withContext(Dispatchers.IO) { runCatching { clientFor(url.trim()).health() }.isSuccess }
            _connection.value = if (reachable) "Connected ✓" else "Can't reach server"
        }
    }

    /** The service owns sync, so it applies the change (reset, re-share recent calls, sync). */
    fun save(url: String) {
        val app = getApplication<Application>()
        app.startForegroundService(
            Intent(app, CallWatcherService::class.java)
                .setAction(CallWatcherService.ACTION_SERVER_CHANGED)
                .putExtra(CallWatcherService.EXTRA_SERVER_URL, url.trim())
        )
        _connection.value = null
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(application) as T
    }

    private companion object {
        const val STATUS_REFRESH_MILLIS = 5_000L
    }
}
