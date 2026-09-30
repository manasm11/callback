package com.shopcallback.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncEventType { CALL, MANUAL_RESOLVE, UNRESOLVE }

/** An event this phone produced that the sync server hasn't confirmed yet. */
@Entity(tableName = "outbox_events")
data class OutboxEventEntity(
    @PrimaryKey val eventId: String,
    val type: SyncEventType,
    val number: String,
    val timestamp: Long,
    val durationSeconds: Int? = null,
    /** "INCOMING" or "OUTGOING" for CALL events, otherwise null. */
    val direction: String? = null
)

/** An event another phone produced, pulled from the sync server and kept for 7 days. */
@Entity(tableName = "remote_events")
data class RemoteEventEntity(
    @PrimaryKey val eventId: String,
    val type: SyncEventType,
    val number: String,
    val timestamp: Long,
    val durationSeconds: Int? = null,
    val direction: String? = null,
    /** Manual events are applied once; CALL events are re-evaluated every time and never marked. */
    val applied: Boolean = false
)
