package com.shopcallback.tracker.calllog

enum class CallDirection { INCOMING, OUTGOING, MISSED }

data class CallLogEntry(
    val rawNumber: String,
    val timestamp: Long,
    val durationSeconds: Int,
    val direction: CallDirection
)
