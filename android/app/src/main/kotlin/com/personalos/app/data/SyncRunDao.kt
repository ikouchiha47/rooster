package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncRunDao {
    @Insert
    suspend fun insert(run: SyncRunEntity): Long

    /** Latest run per source, newest source first — the Events tab read. */
    @Query(Sql.SYNC_RUNS_LATEST_PER_SOURCE)
    fun observeLatestPerSource(): Flow<List<SyncRunEntity>>

    @Query(Sql.SYNC_RUNS_LATEST_PER_SOURCE)
    suspend fun latestPerSource(): List<SyncRunEntity>

    /** Recent runs for one source, newest first — the per-source history. */
    @Query(Sql.SYNC_RUNS_FOR_SOURCE)
    suspend fun runsForSource(
        sourceId: String,
        limit: Int,
    ): List<SyncRunEntity>

    /** How many sources have any run — the Events tab count. */
    @Query(Sql.SYNC_RUNS_SOURCE_COUNT)
    fun observeSourceCount(): Flow<Int>

    /** Bounded history: drops runs older than [cutoffMs]. Returns rows removed. */
    @Query(Sql.SYNC_RUNS_PRUNE)
    suspend fun pruneOlderThan(cutoffMs: Long): Int
}
