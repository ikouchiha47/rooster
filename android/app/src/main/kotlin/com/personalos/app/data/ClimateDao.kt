package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClimateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<ClimateSeriesEntity>)

    @Query("SELECT * FROM climate_series ORDER BY metric ASC, region ASC, period_start ASC")
    fun observeAll(): Flow<List<ClimateSeriesEntity>>

    @Query("SELECT * FROM climate_series ORDER BY metric ASC, region ASC, period_start ASC")
    suspend fun all(): List<ClimateSeriesEntity>

    @Query("DELETE FROM climate_series WHERE source_id = :sourceId")
    suspend fun clearSource(sourceId: String)
}
