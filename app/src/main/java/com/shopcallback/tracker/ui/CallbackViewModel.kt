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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CallbackViewModel(
    application: Application,
    private val dao: CallbackThreadDao = CallbackDatabase.getInstance(application).callbackThreadDao(),
    now: () -> Long = System::currentTimeMillis
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
            dao.markResolved(phoneNumber, CallbackStatus.RESOLVED, System.currentTimeMillis(), ResolvedReason.MANUAL)
        }
    }

    fun unresolve(phoneNumber: String) {
        viewModelScope.launch { dao.reopen(phoneNumber) }
    }

    fun callBackIntent(phoneNumber: String): Intent =
        Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CallbackViewModel(application) as T
    }
}
