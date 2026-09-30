package com.shopcallback.tracker.sync

import android.util.Log
import com.shopcallback.tracker.calllog.CallLogScanner
import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.data.SyncEventDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Exchanges events with the sync server: upload this phone's outbox, pull the other phones'
 * events, then apply them. Blocking network I/O: run on a background dispatcher.
 */
class SyncEngine(
    private val settings: SyncSettings,
    private val syncDao: SyncEventDao,
    private val applier: RemoteEventApplier,
    /** This phone's answered/outgoing calls from the past week, re-shared when the server changes. */
    private val recentCallEvents: suspend () -> List<OutboxEventEntity>,
    private val clientFor: (String) -> SyncClient = ::SyncClient,
    private val now: () -> Long = System::currentTimeMillis
) {
    /**
     * Runs one pass: purges expired local events, uploads the outbox, pulls other phones' events,
     * then applies them. Returns true if the server was reached this pass. Never throws (except
     * cancellation) — any failure, including a local storage error, is logged and reported as an
     * unreached pass, since this runs from an endless retry loop that must never be broken by it.
     */
    suspend fun syncOnce(): Boolean = passLock.withLock {
        try {
            val cutoff = now() - CallLogScanner.MAX_MISSED_CALL_AGE_MILLIS
            syncDao.deleteOutboxBefore(cutoff)
            syncDao.deleteRemoteBefore(cutoff)

            val url = settings.serverUrl
            if (url.isBlank()) return@withLock false

            val client = clientFor(url)
            val reached = try {
                upload(client)
                pull(client)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not reach sync server; will retry", e)
                false
            }
            applier.apply()
            if (reached) settings.lastSyncAt = now()
            reached
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync pass failed; will retry", e)
            false
        }
    }

    /** The user saved a server address: forget the old server and re-share the past week's calls. */
    suspend fun onServerUrlChanged(url: String) = passLock.withLock {
        settings.serverUrl = url.trim()
        settings.serverId = null
        settings.cursor = 0L
        settings.lastSyncAt = null
        if (settings.serverUrl.isNotBlank()) syncDao.enqueue(safeRecentCallEvents())
    }

    private suspend fun upload(client: SyncClient) {
        while (true) {
            val batch = syncDao.outboxBatch(UPLOAD_BATCH_SIZE)
            if (batch.isEmpty()) return
            when (val result = client.upload(settings.deviceId, batch)) {
                is SyncClient.UploadResult.Accepted -> noteServer(result.serverId)
                SyncClient.UploadResult.Rejected -> Log.w(TAG, "server rejected ${batch.size} events; dropping them")
            }
            syncDao.deleteOutbox(batch.map { it.eventId })
        }
    }

    private suspend fun pull(client: SyncClient) {
        while (true) {
            val page = client.pull(settings.deviceId, settings.cursor)
            if (noteServer(page.serverId)) {
                // Server was reset: send it this phone's recent calls, then pull again from the start.
                upload(client)
                continue
            }
            syncDao.insertRemote(page.events.map { it.event })
            page.events.maxOfOrNull { it.seq }?.let { settings.cursor = it }
            if (page.events.size < PULL_PAGE_SIZE) return
        }
    }

    /** Records the server's ID. If it changed (server database reset), starts over and returns true. */
    private suspend fun noteServer(serverId: String): Boolean {
        val known = settings.serverId
        settings.serverId = serverId
        if (known == null || known == serverId) return false
        settings.cursor = 0L
        syncDao.enqueue(safeRecentCallEvents())
        return true
    }

    private suspend fun safeRecentCallEvents(): List<OutboxEventEntity> =
        try {
            recentCallEvents()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not read recent calls to re-share", e)
            emptyList()
        }

    companion object {
        private const val TAG = "SyncEngine"
        /** Must match PAGE_SIZE in server/callback_sync_server.py. */
        const val PULL_PAGE_SIZE = 500
        const val UPLOAD_BATCH_SIZE = 200
        /** Shared by every instance so the service's and any other caller's passes never overlap. */
        private val passLock = Mutex()
    }
}
