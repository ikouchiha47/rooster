package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannerDao {
    @Query("SELECT * FROM planner_workspaces ORDER BY is_default DESC, name ASC")
    fun observeWorkspaces(): Flow<List<PlannerWorkspaceEntity>>

    @Query("SELECT * FROM planner_workspaces WHERE is_default = 1 LIMIT 1")
    suspend fun defaultWorkspace(): PlannerWorkspaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putWorkspace(workspace: PlannerWorkspaceEntity)

    @Query("UPDATE planner_workspaces SET is_default = 0")
    suspend fun clearDefaultWorkspaces()

    @Query("UPDATE planner_workspaces SET is_default = 1 WHERE id = :id")
    suspend fun markDefaultWorkspace(id: String)

    @Query(
        """
        SELECT * FROM planner_items
        WHERE start_at >= :fromMs AND start_at < :toMs
        ORDER BY start_at ASC, id ASC
        """,
    )
    fun observeRange(
        fromMs: Long,
        toMs: Long,
    ): Flow<List<PlannerItemEntity>>

    @Query("SELECT * FROM planner_items ORDER BY start_at ASC, id ASC")
    fun observeAll(): Flow<List<PlannerItemEntity>>

    @Query("SELECT * FROM planner_items WHERE id = :id")
    suspend fun item(id: String): PlannerItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putItem(item: PlannerItemEntity)

    @Query("DELETE FROM planner_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Query("UPDATE planner_items SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun setStatus(
        id: String,
        status: String,
        updatedAt: Long,
    )
}
