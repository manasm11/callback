package com.shopcallback.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [CallbackThreadEntity::class], version = 1, exportSchema = false)
@TypeConverters(CallbackTypeConverters::class)
abstract class CallbackDatabase : RoomDatabase() {
    abstract fun callbackThreadDao(): CallbackThreadDao

    companion object {
        @Volatile private var instance: CallbackDatabase? = null

        fun getInstance(context: Context): CallbackDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CallbackDatabase::class.java,
                    "callback_tracker.db"
                ).build().also { instance = it }
            }
    }
}
