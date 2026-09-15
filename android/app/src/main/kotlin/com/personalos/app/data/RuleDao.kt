package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    /** The repository's read surface: one `Flow<List<Rule>>` (ADR 0002). */
    @Query(Sql.RULES_ALL)
    fun observeAll(): Flow<List<RuleEntity>>

    @Query(Sql.RULES_ALL)
    suspend fun all(): List<RuleEntity>

    /** Enabled rows of any kind — what the ingestor polls. */
    @Query(Sql.RULES_ENABLED)
    suspend fun enabled(): List<RuleEntity>

    @Query(Sql.RULES_COUNT)
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rules: List<RuleEntity>): List<Long>

    /**
     * Guarded: `seeded` rows never match, so enabling/disabling a locked seed
     * is a no-op here and an error in [RuleRepository]. Returns rows touched.
     */
    @Query(Sql.RULES_UPDATE_ENABLED)
    suspend fun updateEnabled(
        id: String,
        enabled: Boolean,
    ): Int

    /**
     * Guarded: only user rows delete. Returns rows removed.
     */
    @Query(Sql.RULES_DELETE_USER_ONLY)
    suspend fun deleteUserOnly(id: String): Int
}
