package com.shopcallback.tracker.contacts

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract

class ContactLookup(private val contentResolver: ContentResolver) {

    fun lookupDisplayName(normalizedNumber: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(normalizedNumber)
        )
        contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                return cursor.getString(nameIdx)
            }
        }
        return null
    }
}
