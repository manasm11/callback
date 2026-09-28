package com.shopcallback.tracker.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CallWatcherService : Service() {

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + (testDispatcher ?: Dispatchers.IO)) }
    private val callLogSource by lazy { testCallLogSource ?: AndroidCallLogSource(contentResolver) }
    private val contactLookup by lazy { testContactLookup ?: ContactLookup(contentResolver) }
    private val scanStateStore: ScanStateStore by lazy { SharedPrefsScanStateStore(applicationContext) }
    private val scanner by lazy { CallLogScanner(dao()) }
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

        serviceScope.launch { scanOnce() }
    }

    suspend fun scanOnce() {
        val since = scanStateStore.getLastScannedAt()
        val now = System.currentTimeMillis()

        val entries = callLogSource.queryEntriesSince(since)
        if (entries.isNotEmpty()) {
            scanner.applyNewEntries(entries)
        }
        scanStateStore.setLastScannedAt(now)

        resolveMissingNames()
        updateNotification()
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

    private suspend fun updateNotification() {
        val pendingCount = dao().observePending().first().size
        val notification = NotificationHelper.buildNotification(applicationContext, pendingCount)
        getSystemService(NotificationManager::class.java).notify(NotificationHelper.NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        var testCallLogSource: CallLogSource? = null
        var testContactLookup: ContactLookup? = null
        var testDispatcher: CoroutineDispatcher? = null
        var testDatabase: CallbackDatabase? = null
    }
}
