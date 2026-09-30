package com.shopcallback.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CallbackStatus { PENDING, RESOLVED }

/** REMOTE_* mean another shop phone reached the customer (see sync). */
enum class ResolvedReason { AUTO_ANSWERED, MANUAL, REMOTE_ANSWERED, REMOTE_MANUAL }

@Entity(tableName = "callback_threads")
data class CallbackThreadEntity(
    @PrimaryKey val phoneNumber: String,
    val displayName: String?,
    val firstMissedAt: Long,
    val lastMissedAt: Long,
    val attemptCount: Int,
    val status: CallbackStatus,
    val resolvedAt: Long?,
    val resolvedReason: ResolvedReason?,
    /** When this callback was last un-resolved; calls before it no longer count as reaching the customer. */
    val reopenedAt: Long? = null
)

/** When the current callback began: its first missed call, or its un-resolve if that was later. */
fun CallbackThreadEntity.callbackStart(): Long = maxOf(firstMissedAt, reopenedAt ?: 0L)
