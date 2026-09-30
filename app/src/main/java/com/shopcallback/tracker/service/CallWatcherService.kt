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
import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.notification.NotificationHelper
import com.shopcallback.tracker.sync.CallbackRulesLock
import com.shopcallback.tracker.sync.OutboxEvents
import com.shopcallback.tracker.sync.RemoteEventApplier
import com.shopcallback.tracker.sync.SyncEngine
import com.shopcallback.tracker.sync.SyncSettings
import com.shopcallback.tracker.util.ScanStateStore
import com.shopcallback.tracker.util.SharedPrefsScanStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class CallWatcherService : Service() {

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + (testDispatcher ?: Dispatchers.IO)) }
    private val callLogSource by lazy { testCallLogSource ?: AndroidCallLogSource(contentResolver) }
    private val contactLookup by lazy { testContactLookup ?: ContactLookup(contentResolver) }
    private val scanStateStore: ScanStateStore by lazy { SharedPrefsScanStateStore(applicationContext) }
    private val scanner by lazy { CallLogScanner(dao()) }
    private val syncSettings by lazy { SyncSettings(applicationContext) }
    private val remoteEventApplier by lazy { RemoteEventApplier(dao(), syncDao()) }
    private val syncEngine by lazy { SyncEngine(syncSettings, syncDao(), remoteEventApplier, ::recentCallEvents) }
    private lateinit var observer: ContentObserver

    private fun database() = testDatabase ?: CallbackDatabase.getInstance(applicationContext)
    private fun dao() = database().callbackThreadDao()
    private fun syncDao() = database().syncEventDao()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(applicationContext)
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(applicationContext, 0))

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                serviceScope.launch { scanAndSync() }
            }
        }
        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)

        serviceScope.launch {
            dao().observePending().collect { pending -> updateNotification(pending.size) }
        }
        serviceScope.launch { scanAndSync() }
        if (!testDisablePolling) {
            serviceScope.launch {
                while (isActive) {
                    delay(SYNC_INTERVAL_MILLIS)
                    syncEngine.syncOnce()
                }
            }
        }
    }

    private suspend fun scanAndSync() {
        scanOnce()
        syncEngine.syncOnce()
    }

    /**
     * Holds [CallbackRulesLock] (which also keeps scans from overlapping) so a sync pass can't apply
     * other phones' events between the scanner reading a callback and writing it back. It calls
     * [RemoteEventApplier.apply] directly, not through [SyncEngine], so the lock is taken only once.
     */
    suspend fun scanOnce() {
        CallbackRulesLock.mutex.withLock {
            try {
                if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    return@withLock
                }
                val lastSeenId = scanStateStore.getLastSeenId()
                val afterDate = if (lastSeenId == SharedPrefsScanStateStore.NOT_SET) {
                    System.currentTimeMillis() - CallLogScanner.MAX_MISSED_CALL_AGE_MILLIS
                } else {
                    0L
                }
                val entries = callLogSource.queryEntries(lastSeenId, afterDate)
                if (entries.isNotEmpty()) {
                    scanner.applyNewEntries(entries)
                    syncDao().enqueue(OutboxEvents.forCalls(syncSettings.deviceId, entries))
                    scanStateStore.setLastSeenId(entries.maxOf { it.id })
                }
                scanner.dropStalePending()
                // Newly scanned missed calls may already have been answered on another phone.
                remoteEventApplier.apply()
                resolveMissingNames()
            } catch (e: Exception) {
                Log.e(TAG, "scanOnce failed", e)
            }
        }
    }

    /** The past week's answered/outgoing calls, re-shared when sync is turned on or the server is reset. */
    private suspend fun recentCallEvents(): List<OutboxEventEntity> {
        val since = System.currentTimeMillis() - CallLogScanner.MAX_MISSED_CALL_AGE_MILLIS
        return OutboxEvents.forCalls(syncSettings.deviceId, callLogSource.queryEntries(afterId = -1L, afterDateMillis = since))
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

    /** Every app open (and every Settings save) lands here, so each one also triggers a sync. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newServerUrl = intent?.takeIf { it.action == ACTION_SERVER_CHANGED }?.getStringExtra(EXTRA_SERVER_URL)
        serviceScope.launch {
            if (newServerUrl != null) {
                try {
                    syncEngine.onServerUrlChanged(newServerUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // An uncaught exception here would crash the app; the sync pass below still runs.
                    Log.e(TAG, "could not switch to the new sync server", e)
                }
            }
            syncEngine.syncOnce()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CallWatcherService"
        private const val SYNC_INTERVAL_MILLIS = 30_000L
        const val ACTION_SERVER_CHANGED = "com.shopcallback.tracker.action.SERVER_CHANGED"
        const val EXTRA_SERVER_URL = "server_url"
        var testCallLogSource: CallLogSource? = null
        var testContactLookup: ContactLookup? = null
        var testDispatcher: CoroutineDispatcher? = null
        var testDatabase: CallbackDatabase? = null
        /** Tests drive the dispatcher to idle, which an endless poll loop would never reach. */
        var testDisablePolling = false
    }
}
