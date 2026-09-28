package com.shopcallback.tracker.calllog

import android.content.ContentResolver
import android.provider.CallLog

class AndroidCallLogSource(private val contentResolver: ContentResolver) : CallLogSource {

    override fun queryEntriesSince(timestampMillis: Long): List<CallLogEntry> {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE
        )
        val entries = mutableListOf<CallLogEntry>()

        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DATE} > ?",
            arrayOf(timestampMillis.toString()),
            "${CallLog.Calls.DATE} ASC"
        )?.use { cursor ->
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
