package com.shopcallback.tracker.calllog

import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.CallbackThreadEntity
import com.shopcallback.tracker.data.ResolvedReason
import com.shopcallback.tracker.util.PhoneNumberNormalizer

class CallLogScanner(private val dao: CallbackThreadDao) {

    suspend fun applyNewEntries(entries: List<CallLogEntry>) {
        entries.sortedBy { it.timestamp }.forEach { entry ->
            val number = PhoneNumberNormalizer.normalize(entry.rawNumber)
            if (number.length < MIN_VALID_NUMBER_LENGTH) return@forEach
            when (entry.direction) {
                CallDirection.MISSED -> handleMissed(entry, number)
                CallDirection.INCOMING, CallDirection.OUTGOING -> handleAnsweredCandidate(entry, number)
            }
        }
    }

    private suspend fun handleMissed(entry: CallLogEntry, number: String) {
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

    private suspend fun handleAnsweredCandidate(entry: CallLogEntry, number: String) {
        if (entry.durationSeconds <= 1) return

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

    companion object {
        private const val MIN_VALID_NUMBER_LENGTH = 5
    }
}
