package com.shopcallback.tracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallbackDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migrating from version 1 keeps existing callbacks and adds the sync tables`() = runBlocking {
        // Recreate a database exactly as version 1 of the app left it (SQL copied from Room's v1 output).
        val file = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
            v1.execSQL(
                "CREATE TABLE IF NOT EXISTS `callback_threads` (`phoneNumber` TEXT NOT NULL, `displayName` TEXT, " +
                    "`firstMissedAt` INTEGER NOT NULL, `lastMissedAt` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, " +
                    "`status` TEXT NOT NULL, `resolvedAt` INTEGER, `resolvedReason` TEXT, PRIMARY KEY(`phoneNumber`))"
            )
            v1.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            v1.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '72f6e56ac9aa7fd9ade168c98827483f')")
            v1.execSQL("INSERT INTO callback_threads VALUES ('9876543210', 'Priya', 100, 200, 2, 'PENDING', NULL, NULL)")
            v1.version = 1
        }

        // Room validates the migrated schema against the entities and throws if MIGRATION_1_2 is wrong.
        val db = Room.databaseBuilder(context, CallbackDatabase::class.java, dbName)
            .addMigrations(CallbackDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val thread = db.callbackThreadDao().findByNumber("9876543210")
            assertEquals("Priya", thread?.displayName)
            assertEquals(2, thread?.attemptCount)
            assertEquals(CallbackStatus.PENDING, thread?.status)
            assertNull(thread?.reopenedAt)

            db.syncEventDao().enqueue(listOf(OutboxEventEntity("d:call:1", SyncEventType.CALL, "9876543210", 300L, 40, "OUTGOING")))
            assertEquals(1, db.syncEventDao().outboxBatch(10).size)
        } finally {
            db.close()
        }
    }
}
