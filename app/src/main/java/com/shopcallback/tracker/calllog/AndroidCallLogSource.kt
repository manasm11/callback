package com.shopcallback.tracker.calllog

import android.content.ContentResolver
import android.provider.CallLog

class AndroidCallLogSource(private val contentResolver: ContentResolver) : CallLogSource {

    override fun queryEntries(afterId: Long, afterDateMillis: Long): List<CallLogEntry> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE
        )
        val entries = mutableListOf<CallLogEntry>()

        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls._ID} > ? AND ${CallLog.Calls.DATE} > ?",
            arrayOf(afterId.toString(), afterDateMillis.toString()),
            "${CallLog.Calls._ID} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)

            while (cursor.moveToNext()) {
                val direction = when (cursor.getInt(typeIdx)) {
                    CallLog.Calls.MISSED_TYPE -> CallDirection.MISSED
                    CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
                    CallLog.Calls.INCOMING_TYPE -> CallDirection.INCOMING
                    else -> null
                } ?: continue
                val number = cursor.getString(numberIdx) ?: continue

                entries.add(
                    CallLogEntry(
                        id = cursor.getLong(idIdx),
                        rawNumber = number,
                        timestamp = cursor.getLong(dateIdx),
                        durationSeconds = cursor.getInt(durationIdx),
                        direction = direction
                    )
                )
            }
        }
        return entries
    }
}
