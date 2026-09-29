package com.shopcallback.tracker.service

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.shopcallback.tracker.calllog.AndroidCallLogSource
import com.shopcallback.tracker.calllog.CallLogScanner
import com.shopcallback.tracker.calllog.CallLogSource
import com.shopcallback.tracker.contacts.ContactLookup
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.notification.NotificationHelper
import com.shopcallback.tracker.util.ScanStateStore
import com.shopcallback.tracker.util.SharedPrefsScanStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class CallWatcherService : Service() {

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + (testDispatcher ?: Dispatchers.IO)) }
    private val callLogSource by lazy { testCallLogSource ?: AndroidCallLogSource(contentResolver) }
    private val contactLookup by lazy { testContactLookup ?: ContactLookup(contentResolver) }
    private val scanStateStore: ScanStateStore by lazy { SharedPrefsScanStateStore(applicationContext) }
    private val scanner by lazy { CallLogScanner(dao()) }
    private val scanMutex = Mutex()
    private lateinit var observer: ContentObserver

    private fun database() = testDatabase ?: CallbackDatabase.getInstance(applicationContext)
    private fun dao() = database().callbackThreadDao()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(applicationContext)
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(applicationContext, 0))

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                serviceScope.launch { scanOnce() }
            }
        }
        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)

        serviceScope.launch {
            dao().observePending().collect { pending -> updateNotification(pending.size) }
        }
        serviceScope.launch { scanOnce() }
    }

    suspend fun scanOnce() {
        scanMutex.withLock {
            try {
                if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    return@withLock
                }
                val lastSeenId = scanStateStore.getLastSeenId()
                val afterDate = if (lastSeenId == SharedPrefsScanStateStore.NOT_SET) {
                    System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
                } else {
                    0L
                }
                val entries = callLogSource.queryEntries(lastSeenId, afterDate)
                if (entries.isNotEmpty()) {
                    scanner.applyNewEntries(entries)
                    scanStateStore.setLastSeenId(entries.maxOf { it.id })
                }
                resolveMissingNames()
            } catch (e: Exception) {
                Log.e(TAG, "scanOnce failed", e)
            }
        }
    }

    private suspend fun resolveMissingNames() {
        dao().observePending().first()
            .filter { it.displayName == null }
            .forEach { thread ->
                contactLookup.lookupDisplayName(thread.phoneNumber)?.let { name ->
                    dao().updateDisplayNameIfMissing(thread.phoneNumber, name)
                }
            }
    }

    private fun updateNotification(pendingCount: Int) {
        val notification = NotificationHelper.buildNotification(applicationContext, pendingCount)
        getSystemService(NotificationManager::class.java).notify(NotificationHelper.NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CallWatcherService"
        var testCallLogSource: CallLogSource? = null
        var testContactLookup: ContactLookup? = null
        var testDispatcher: CoroutineDispatcher? = null
        var testDatabase: CallbackDatabase? = null
    }
}
