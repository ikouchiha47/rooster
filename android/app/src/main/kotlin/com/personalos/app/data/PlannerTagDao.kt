package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannerTagDao {
    @Query("SELECT * FROM planner_tags ORDER BY sort ASC, label ASC")
    fun observeTags(): Flow<List<PlannerTagEntity>>

    @Query("SELECT * FROM planner_item_tags WHERE item_id = :itemId")
    suspend fun tagsForItem(itemId: String): List<PlannerItemTagEntity>

    @Query("SELECT * FROM planner_item_tags WHERE item_id IN (:itemIds)")
    suspend fun tagsForItems(itemIds: List<String>): List<PlannerItemTagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putItemTags(rows: List<PlannerItemTagEntity>)

    @Query("DELETE FROM planner_item_tags WHERE item_id = :itemId")
    suspend fun clearItemTags(itemId: String)
}
