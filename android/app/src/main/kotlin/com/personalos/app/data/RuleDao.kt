package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    /** The repository's read surface: one `Flow<List<Rule>>` (ADR 0003). */
    @Query(Sql.RULES_ALL)
    fun observeAll(): Flow<List<RuleEntity>>

    @Query(Sql.RULES_ALL)
    suspend fun all(): List<RuleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rules: List<RuleEntity>): List<Long>
}
