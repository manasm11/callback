package com.shopcallback.tracker.calllog

interface CallLogSource {
    fun queryEntries(afterId: Long, afterDateMillis: Long): List<CallLogEntry>
}
