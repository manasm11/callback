package com.shopcallback.tracker.calllog

interface CallLogSource {
    fun queryEntriesSince(timestampMillis: Long): List<CallLogEntry>
}
