package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemRuleDao {
    @Query(Sql.ITEM_RULES_ALL)
    fun observeAll(): Flow<List<ItemRuleEntity>>

    @Query(Sql.ITEM_RULES_ALL)
    suspend fun all(): List<ItemRuleEntity>

    /** Materialised matches: append-only, first write wins. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(matches: List<ItemRuleEntity>): List<Long>
}
