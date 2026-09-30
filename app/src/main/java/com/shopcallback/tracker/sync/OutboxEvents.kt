package com.shopcallback.tracker.sync

import com.shopcallback.tracker.calllog.CallDirection
import com.shopcallback.tracker.calllog.CallLogEntry
import com.shopcallback.tracker.calllog.CallLogScanner
import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.data.SyncEventType
import com.shopcallback.tracker.util.PhoneNumberNormalizer
import java.util.UUID

/** Builds the events this phone shares with the others. Missed calls are never shared. */
object OutboxEvents {
    fun forCalls(deviceId: String, entries: List<CallLogEntry>): List<OutboxEventEntity> =
        entries.mapNotNull { entry ->
            if (entry.direction == CallDirection.MISSED || entry.durationSeconds <= 1) return@mapNotNull null
            val number = PhoneNumberNormalizer.normalize(entry.rawNumber)
            if (number.length < CallLogScanner.MIN_VALID_NUMBER_LENGTH) return@mapNotNull null
            OutboxEventEntity(
                eventId = "$deviceId:call:${entry.id}",
                type = SyncEventType.CALL,
                number = number,
                timestamp = entry.timestamp,
                durationSeconds = entry.durationSeconds,
                direction = entry.direction.name
            )
        }

    fun manual(deviceId: String, type: SyncEventType, number: String, timestamp: Long): OutboxEventEntity =
        OutboxEventEntity(
            eventId = "$deviceId:manual:${UUID.randomUUID()}",
            type = type,
            number = number,
            timestamp = timestamp
        )
}
