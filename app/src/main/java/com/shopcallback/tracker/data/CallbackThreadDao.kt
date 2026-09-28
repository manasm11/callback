package com.shopcallback.tracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallbackThreadDao {
    @Query("SELECT * FROM callback_threads WHERE phoneNumber = :phoneNumber")
    suspend fun findByNumber(phoneNumber: String): CallbackThreadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(thread: CallbackThreadEntity)

    @Query("SELECT * FROM callback_threads WHERE status = 'PENDING' ORDER BY firstMissedAt ASC")
    fun observePending(): Flow<List<CallbackThreadEntity>>

    @Query("SELECT * FROM callback_threads WHERE status = 'RESOLVED' ORDER BY resolvedAt DESC")
    fun observeHistory(): Flow<List<CallbackThreadEntity>>

    @Query(
        "UPDATE callback_threads SET status = :status, resolvedAt = :resolvedAt, resolvedReason = :reason " +
            "WHERE phoneNumber = :phoneNumber"
    )
    suspend fun markResolved(phoneNumber: String, status: CallbackStatus, resolvedAt: Long, reason: ResolvedReason)

    @Query(
        "UPDATE callback_threads SET displayName = :displayName " +
            "WHERE phoneNumber = :phoneNumber AND displayName IS NULL"
    )
    suspend fun updateDisplayNameIfMissing(phoneNumber: String, displayName: String)
}
