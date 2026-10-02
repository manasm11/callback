package com.shopcallback.tracker.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shopcallback.tracker.calllog.CallLogScanner
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.CallbackThreadEntity
import com.shopcallback.tracker.data.ResolvedReason
import com.shopcallback.tracker.data.SyncEventDao
import com.shopcallback.tracker.data.SyncEventType
import com.shopcallback.tracker.sync.OutboxEvents
import com.shopcallback.tracker.sync.SyncSettings
import com.shopcallback.tracker.widget.PendingCallbacksWidget
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CallbackViewModel(
    application: Application,
    private val dao: CallbackThreadDao = CallbackDatabase.getInstance(application).callbackThreadDao(),
    private val now: () -> Long = System::currentTimeMillis,
    private val syncDao: SyncEventDao = CallbackDatabase.getInstance(application).syncEventDao(),
    private val syncSettings: SyncSettings = SyncSettings(application)
) : AndroidViewModel(application) {

    init {
        // The service drops stale callbacks when it scans, but that only happens on call-log
        // changes; also drop them on open so a week-old callback never lingers on screen.
        viewModelScope.launch { CallLogScanner(dao, now).dropStalePending() }
    }

    val pendingThreads: StateFlow<List<CallbackThreadEntity>> = dao.observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val historyThreads: StateFlow<List<CallbackThreadEntity>> = dao.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markResolvedManually(phoneNumber: String) {
        viewModelScope.launch {
            val at = now()
            dao.markResolved(phoneNumber, CallbackStatus.RESOLVED, at, ResolvedReason.MANUAL)
            share(SyncEventType.MANUAL_RESOLVE, phoneNumber, at)
            PendingCallbacksWidget.refreshAll(getApplication())
        }
    }

    fun unresolve(phoneNumber: String) {
        viewModelScope.launch {
            val at = now()
            dao.reopen(phoneNumber, at)
            share(SyncEventType.UNRESOLVE, phoneNumber, at)
            PendingCallbacksWidget.refreshAll(getApplication())
        }
    }

    /** Queues a manual action for the other phones; the service uploads it on its next sync. */
    private suspend fun share(type: SyncEventType, phoneNumber: String, at: Long) {
        syncDao.enqueue(listOf(OutboxEvents.manual(syncSettings.deviceId, type, phoneNumber, at)))
    }

    fun callBackIntent(phoneNumber: String): Intent =
        Intent(Intent.ACTION_CALL, Uri.fromParts("tel", phoneNumber, null))

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CallbackViewModel(application) as T
    }
}
