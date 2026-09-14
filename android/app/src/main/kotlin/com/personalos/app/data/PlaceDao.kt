package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaceDao {
    @Query(Sql.PLACES_COUNT)
    suspend fun count(): Int

    @Query(Sql.PLACES_ALL)
    suspend fun all(): List<PlaceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(places: List<PlaceEntity>): List<Long>
}
