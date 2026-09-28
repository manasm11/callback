package com.shopcallback.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CallbackStatus { PENDING, RESOLVED }
enum class ResolvedReason { AUTO_ANSWERED, MANUAL }

@Entity(tableName = "callback_threads")
data class CallbackThreadEntity(
    @PrimaryKey val phoneNumber: String,
    val displayName: String?,
    val firstMissedAt: Long,
    val lastMissedAt: Long,
    val attemptCount: Int,
    val status: CallbackStatus,
    val resolvedAt: Long?,
    val resolvedReason: ResolvedReason?
)
