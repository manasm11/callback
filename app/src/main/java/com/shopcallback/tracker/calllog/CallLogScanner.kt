package com.shopcallback.tracker.calllog

import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.CallbackThreadEntity
import com.shopcallback.tracker.data.ResolvedReason
import com.shopcallback.tracker.util.PhoneNumberNormalizer

class CallLogScanner(private val dao: CallbackThreadDao) {

    suspend fun applyNewEntries(entries: List<CallLogEntry>) {
        entries.sortedBy { it.timestamp }.forEach { entry ->
            when (entry.direction) {
                CallDirection.MISSED -> handleMissed(entry)
                CallDirection.INCOMING, CallDirection.OUTGOING -> handleAnsweredCandidate(entry)
            }
        }
    }

    private suspend fun handleMissed(entry: CallLogEntry) {
        val number = PhoneNumberNormalizer.normalize(entry.rawNumber)
        val existing = dao.findByNumber(number)

        val updated = if (existing != null && existing.status == CallbackStatus.PENDING) {
            existing.copy(lastMissedAt = entry.timestamp, attemptCount = existing.attemptCount + 1)
        } else {
            CallbackThreadEntity(
                phoneNumber = number,
                displayName = existing?.displayName,
                firstMissedAt = entry.timestamp,
                lastMissedAt = entry.timestamp,
                attemptCount = 1,
                status = CallbackStatus.PENDING,
                resolvedAt = null,
                resolvedReason = null
            )
        }
        dao.upsert(updated)
    }

    private suspend fun handleAnsweredCandidate(entry: CallLogEntry) {
        if (entry.durationSeconds <= 1) return

        val number = PhoneNumberNormalizer.normalize(entry.rawNumber)
        val existing = dao.findByNumber(number) ?: return

        if (existing.status == CallbackStatus.PENDING && entry.timestamp > existing.firstMissedAt) {
            dao.markResolved(
                phoneNumber = number,
                status = CallbackStatus.RESOLVED,
                resolvedAt = entry.timestamp,
                reason = ResolvedReason.AUTO_ANSWERED
            )
        }
    }
}
