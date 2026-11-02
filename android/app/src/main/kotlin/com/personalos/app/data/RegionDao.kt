package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RegionDao {
    @Query("SELECT * FROM saved_regions ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SavedRegionEntity>>

    @Query("SELECT * FROM saved_regions ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<SavedRegionEntity>

    @Query("SELECT * FROM saved_regions WHERE id = :id")
    suspend fun byId(id: String): SavedRegionEntity?

    @Query("SELECT * FROM saved_regions WHERE source_kind = :kind AND source_id = :sourceId LIMIT 1")
    suspend fun bySource(
        kind: String,
        sourceId: String,
    ): SavedRegionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(region: SavedRegionEntity)

    @Query("DELETE FROM saved_regions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM region_defaults WHERE region_id = :id")
    suspend fun clearDefaultsFor(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDefault(row: RegionDefaultEntity)

    @Query("DELETE FROM region_defaults WHERE consumer = :consumer")
    suspend fun clearDefault(consumer: String)

    @Query("SELECT * FROM region_defaults WHERE consumer = :consumer")
    fun observeDefaultRow(consumer: String): Flow<RegionDefaultEntity?>

    @Query(
        """
        SELECT r.* FROM saved_regions r
        INNER JOIN region_defaults d ON d.region_id = r.id
        WHERE d.consumer = :consumer
        LIMIT 1
        """,
    )
    fun observeDefault(consumer: String): Flow<SavedRegionEntity?>

    @Query(
        """
        SELECT r.* FROM saved_regions r
        INNER JOIN region_defaults d ON d.region_id = r.id
        WHERE d.consumer = :consumer
        LIMIT 1
        """,
    )
    suspend fun defaultRegion(consumer: String): SavedRegionEntity?
}
