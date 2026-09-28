package com.shopcallback.tracker.data

import androidx.room.TypeConverter

class CallbackTypeConverters {
    @TypeConverter
    fun statusToString(status: CallbackStatus): String = status.name

    @TypeConverter
    fun stringToStatus(value: String): CallbackStatus = CallbackStatus.valueOf(value)

    @TypeConverter
    fun reasonToString(reason: ResolvedReason?): String? = reason?.name

    @TypeConverter
    fun stringToReason(value: String?): ResolvedReason? = value?.let { ResolvedReason.valueOf(it) }
}
