package com.shopcallback.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CallbackThreadEntity::class, OutboxEventEntity::class, RemoteEventEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(CallbackTypeConverters::class)
abstract class CallbackDatabase : RoomDatabase() {
    abstract fun callbackThreadDao(): CallbackThreadDao
    abstract fun syncEventDao(): SyncEventDao

    companion object {
        @Volatile private var instance: CallbackDatabase? = null

        /** v2 adds multi-phone sync. Updates must keep existing callbacks, so this is a real migration. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `callback_threads` ADD COLUMN `reopenedAt` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outbox_events` (`eventId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`number` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `durationSeconds` INTEGER, " +
                        "`direction` TEXT, PRIMARY KEY(`eventId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `remote_events` (`eventId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`number` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `durationSeconds` INTEGER, " +
                        "`direction` TEXT, `applied` INTEGER NOT NULL, PRIMARY KEY(`eventId`))"
                )
            }
        }

        fun getInstance(context: Context): CallbackDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CallbackDatabase::class.java,
                    "callback_tracker.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
