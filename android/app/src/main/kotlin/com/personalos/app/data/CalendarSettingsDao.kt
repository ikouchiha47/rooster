package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarSettingsDao {
    @Query("SELECT * FROM calendar_settings WHERE surface = :surface")
    fun observeSettings(surface: String): Flow<CalendarSettingsEntity?>

    @Query("SELECT * FROM calendar_settings WHERE surface = :surface")
    suspend fun settings(surface: String): CalendarSettingsEntity?

    @Query("SELECT * FROM calendar_selections WHERE surface = :surface")
    fun observeSelections(surface: String): Flow<List<CalendarSelectionEntity>>

    @Query("SELECT * FROM calendar_selections WHERE surface = :surface")
    suspend fun selections(surface: String): List<CalendarSelectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSettings(row: CalendarSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSelections(rows: List<CalendarSelectionEntity>)

    @Query("DELETE FROM calendar_selections WHERE surface = :surface")
    suspend fun clearSelections(surface: String)
}
