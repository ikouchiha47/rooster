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

    /**
     * Mentions for a whole page at once, so lists do one batched read per page
     * instead of one query per row. Never called with an empty list — `IN ()`
     * matches nothing in some SQLite builds and errors in others.
     */
    @Query(Sql.MENTIONS_FOR_ITEMS)
    suspend fun forItems(itemIds: List<String>): List<MentionEntity>

    /** One batch of the mention backfill walk, in insertion order. */
    @Query(Sql.MENTIONS_MISSING_ITEMS)
    suspend fun missingItems(
        afterId: Long,
        limit: Int,
    ): List<MentionCandidate>
}
