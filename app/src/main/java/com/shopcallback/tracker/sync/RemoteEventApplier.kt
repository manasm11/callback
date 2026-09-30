package com.shopcallback.tracker.sync

import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.ResolvedReason
import com.shopcallback.tracker.data.SyncEventDao
import com.shopcallback.tracker.data.SyncEventType
import com.shopcallback.tracker.data.latestActivity
import kotlinx.coroutines.flow.first

/** Applies other phones' events to this phone's callbacks. Safe to run any number of times. */
class RemoteEventApplier(
    private val threadDao: CallbackThreadDao,
    private val syncDao: SyncEventDao
) {
    suspend fun apply() {
        applyManualEvents()
        resolveByRemoteCalls()
    }

    /** Mark resolved / Un-resolve from other phones: applied once each, oldest first. */
    private suspend fun applyManualEvents() {
        val events = syncDao.unappliedManualEvents()
        events.forEach { event ->
            val thread = threadDao.findByNumber(event.number) ?: return@forEach
            when (event.type) {
                SyncEventType.MANUAL_RESOLVE ->
                    if (thread.status == CallbackStatus.PENDING && thread.latestActivity() < event.timestamp) {
                        threadDao.markResolved(event.number, CallbackStatus.RESOLVED, event.timestamp, ResolvedReason.REMOTE_MANUAL)
                    }
                SyncEventType.UNRESOLVE ->
                    if (thread.status == CallbackStatus.RESOLVED && (thread.resolvedAt ?: 0L) <= event.timestamp) {
                        threadDao.reopen(event.number, event.timestamp)
                    }
                SyncEventType.CALL -> Unit
            }
        }
        if (events.isNotEmpty()) syncDao.markApplied(events.map { it.eventId })
    }

    /**
     * Calls from other phones are re-checked every time rather than applied once, so the result
     * doesn't depend on whether the remote call or the local missed call was seen first. Only calls
     * after the latest miss count, so a call that arrives late can't clear a newer missed call.
     */
    private suspend fun resolveByRemoteCalls() {
        threadDao.observePending().first().forEach { thread ->
            val callAt = syncDao.earliestRemoteCallAfter(thread.phoneNumber, thread.latestActivity()) ?: return@forEach
            threadDao.markResolved(thread.phoneNumber, CallbackStatus.RESOLVED, callAt, ResolvedReason.REMOTE_ANSWERED)
        }
    }
}
