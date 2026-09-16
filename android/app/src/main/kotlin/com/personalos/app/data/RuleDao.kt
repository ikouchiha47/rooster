package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    /** The read surface: one `Flow<List<RuleEntity>>` (ADR 0003). */
    @Query(Sql.RULES_ALL)
    fun observeAll(): Flow<List<RuleEntity>>

    @Query(Sql.RULES_ALL)
    suspend fun all(): List<RuleEntity>

    @Query(Sql.RULES_COUNT)
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rules: List<RuleEntity>): List<Long>

    /**
     * Guarded: `seeded` rows never match, so editing a locked seed is a no-op
     * here and an error in [RuleRepository]. Returns rows touched. Every edit
     * stamps `updated_at`, so the row says when it last changed.
     */
    @Query(Sql.RULES_UPDATE_USER_ONLY)
    suspend fun updateUserOnly(
        id: String,
        name: String,
        conditionJson: String,
        actionJson: String,
        color: String?,
        position: Long,
        updatedAt: Long,
    ): Int

    /**
     * Guarded: only user rows enable/disable. Returns rows touched.
     */
    @Query(Sql.RULES_UPDATE_ENABLED_USER_ONLY)
    suspend fun updateEnabledUserOnly(
        id: String,
        enabled: Boolean,
        updatedAt: Long,
    ): Int

    /**
     * Guarded: only user rows delete. Returns rows removed.
     */
    @Query(Sql.RULES_DELETE_USER_ONLY)
    suspend fun deleteUserOnly(id: String): Int
}
