package com.shopcallback.tracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(events: List<OutboxEventEntity>)

    @Query("SELECT * FROM outbox_events ORDER BY timestamp ASC LIMIT :limit")
    suspend fun outboxBatch(limit: Int): List<OutboxEventEntity>

    @Query("DELETE FROM outbox_events WHERE eventId IN (:eventIds)")
    suspend fun deleteOutbox(eventIds: List<String>)

    @Query("DELETE FROM outbox_events WHERE timestamp < :cutoff")
    suspend fun deleteOutboxBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM outbox_events")
    fun observeOutboxCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRemote(events: List<RemoteEventEntity>)

    @Query("SELECT * FROM remote_events WHERE type != 'CALL' AND applied = 0 ORDER BY timestamp ASC")
    suspend fun unappliedManualEvents(): List<RemoteEventEntity>

    @Query("UPDATE remote_events SET applied = 1 WHERE eventId IN (:eventIds)")
    suspend fun markApplied(eventIds: List<String>)

    @Query(
        "SELECT MIN(timestamp) FROM remote_events " +
            "WHERE type = 'CALL' AND number = :number AND durationSeconds > 1 AND timestamp > :after"
    )
    suspend fun earliestRemoteCallAfter(number: String, after: Long): Long?

    @Query("DELETE FROM remote_events WHERE timestamp < :cutoff")
    suspend fun deleteRemoteBefore(cutoff: Long)
}
