package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarDao {
    /** Feed rows are authoritative: same uid, corrected date, one row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CalendarDateEntity>): List<Long>

    /** Upcoming observances for the chosen regions, soonest first. */
    @Query(Sql.CALENDAR_UPCOMING)
    fun observeUpcoming(
        regions: List<String>,
        fromMs: Long,
        toMs: Long,
    ): Flow<List<CalendarDateEntity>>

    @Query(Sql.CALENDAR_UPCOMING)
    suspend fun upcoming(
        regions: List<String>,
        fromMs: Long,
        toMs: Long,
    ): List<CalendarDateEntity>

    @Query(Sql.CALENDAR_COUNT)
    suspend fun count(): Int

    /** Drops rows this feed no longer lists, without touching another feed's rows. */
    @Query(Sql.CALENDAR_DELETE_STALE)
    suspend fun deleteStaleForSource(
        source: String,
        fetchedBefore: Long,
    ): Int
}
