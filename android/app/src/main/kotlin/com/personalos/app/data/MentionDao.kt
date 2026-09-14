package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MentionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(mentions: List<MentionEntity>): List<Long>

    @Query(Sql.MENTIONS_FOR_ITEM)
    suspend fun forItem(itemId: String): List<MentionEntity>

    /** One batch of the mention backfill walk, in insertion order. */
    @Query(Sql.MENTIONS_MISSING_ITEMS)
    suspend fun missingItems(
        afterId: Long,
        limit: Int,
    ): List<MentionCandidate>
}
